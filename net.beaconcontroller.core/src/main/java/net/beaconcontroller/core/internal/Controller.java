/**
 * Copyright 2011, Stanford University. This file is licensed under GPL v2 plus
 * a special exception, as described in included LICENSE_EXCEPTION.txt.
 */
/**
 *
 */
package net.beaconcontroller.core.internal;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import net.beaconcontroller.core.IBeaconProvider;
import net.beaconcontroller.core.IOFInitializerListener;
import net.beaconcontroller.core.IOFMessageListener;
import net.beaconcontroller.core.IOFMessageListener.Command;
import net.beaconcontroller.core.IOFSwitch;
import net.beaconcontroller.core.IOFSwitchFilter;
import net.beaconcontroller.core.IOFSwitchListener;
import net.beaconcontroller.core.io.internal.OFStream;
import net.beaconcontroller.core.io.internal.SelectListener;
import net.beaconcontroller.core.io.internal.IOLoop;
import net.beaconcontroller.packet.IPv4;

import org.openflow.io.OFMessageInStream;
import org.openflow.io.OFMessageOutStream;
import org.openflow.protocol.OFEchoReply;
import org.openflow.protocol.OFEchoRequest;
import org.openflow.protocol.OFError;
import org.openflow.protocol.OFError.OFBadActionCode;
import org.openflow.protocol.OFError.OFBadRequestCode;
import org.openflow.protocol.OFError.OFErrorType;
import org.openflow.protocol.OFError.OFFlowModFailedCode;
import org.openflow.protocol.OFError.OFHelloFailedCode;
import org.openflow.protocol.OFError.OFPortModFailedCode;
import org.openflow.protocol.OFError.OFQueueOpFailedCode;
import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFFlowMod;
import org.openflow.protocol.OFGetConfigReply;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFSetConfig;
import org.openflow.protocol.OFType;
import org.openflow.protocol.factory.BasicFactory;
import org.openflow.util.U16;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author David Erickson (daviderickson@cs.stanford.edu) - 04/04/10
 *
 */
public class Controller implements IBeaconProvider, SelectListener {
    protected static Logger log = LoggerFactory.getLogger(Controller.class);
    protected static int LIVENESS_POLL_INTERVAL = 1000;
    protected static int LIVENESS_TIMEOUT = 5000;
    protected static String SWITCH_REQUIREMENTS_TIMER_KEY = "SW_REQ_TIMER";

    protected ConcurrentHashMap<Long, IOFSwitchExt> activeSwitches;
    protected CopyOnWriteArraySet<IOFSwitchExt> allSwitches;
    protected Map<String,String> callbackOrdering;
    protected boolean deletePreExistingFlows = true;
    protected ExecutorService es;
    protected BasicFactory factory;
    protected boolean immediate = false;
    protected CopyOnWriteArrayList<IOFInitializerListener> initializerList;
    protected ConcurrentHashMap<IOFSwitchExt, CopyOnWriteArrayList<IOFInitializerListener>> initializerMap;
    protected String listenAddress;
    protected int listenPort = 6633;
    protected IOLoop listenerIOLoop;
    protected volatile boolean listenerStarted = false;
    protected ServerSocketChannel listenSock;
    protected Timer livenessTimer;
    protected ConcurrentMap<OFType, List<IOFMessageListener>> messageListeners;
    protected boolean noDelay = true;
    protected volatile boolean shuttingDown = false;
    protected Set<IOFSwitchListener> switchListeners;
    protected List<IOLoop> switchIOLoops;
    protected Integer threadCount;
    protected BlockingQueue<Update> updates;
    protected Thread updatesThread;

    protected class Update {
        public IOFSwitch sw;
        public boolean added;

        public Update(IOFSwitch sw, boolean added) {
            this.sw = sw;
            this.added = added;
        }
    }

    /**
     * 
     */
    public Controller() {
        this.messageListeners =
            new ConcurrentHashMap<OFType, List<IOFMessageListener>>();
        this.switchListeners = new CopyOnWriteArraySet<IOFSwitchListener>();
        this.updates = new LinkedBlockingQueue<Update>();
    }

    public void handleEvent(SelectionKey key, Object arg) throws IOException {
        if (arg instanceof ServerSocketChannel)
            handleListenEvent(key, (ServerSocketChannel)arg);
        else
            handleSwitchEvent(key, (IOFSwitchExt) arg);
    }

    protected void handleListenEvent(SelectionKey key, ServerSocketChannel ssc)
            throws IOException {
        SocketChannel sock = listenSock.accept();
        log.info("Switch connected from {}", sock.toString());
        sock.socket().setTcpNoDelay(this.noDelay);
        sock.configureBlocking(false);
        sock.socket().setSendBufferSize(1024*1024);
        OFSwitchImpl sw = new OFSwitchImpl();

        // Try to even the # of switches per thread
        // TODO something more intelligent here based on load
        IOLoop sl = null;
        for (IOLoop loop : switchIOLoops) {
            if (sl == null || loop.getStreams().size() < sl.getStreams().size())
                sl = loop;
        }

        // register initially with no ops because we need the key to init the stream
        SelectionKey switchKey = sl.registerBlocking(sock, 0, sw);
        OFStream stream = new OFStream(sock, factory, switchKey, sl);
        stream.setImmediate(this.immediate);
        sw.setInputStream(stream);
        sw.setOutputStream(stream);
        sw.setSocketChannel(sock);
        sw.setBeaconProvider(this);
        sw.transitionToState(SwitchState.HELLO_SENT);

        addSwitch(sw);

        // Send HELLO
        stream.write(factory.getMessage(OFType.HELLO));

        // register for read
        switchKey.interestOps(SelectionKey.OP_READ);
        sl.addStream(stream);
        log.info("Added switch {} to IOLoop {}", sw, sl);
        sl.wakeup();
    }

    protected void handleSwitchEvent(SelectionKey key, IOFSwitchExt sw) {
        OFStream out = ((OFStream)sw.getOutputStream());
        OFStream in = (OFStream) sw.getInputStream();
        try {
            /**
             * A key may not be valid here if it has been disconnected while
             * it was in a select operation.
             */
            if (!key.isValid())
                return;

            if (key.isReadable()) {
                List<OFMessage> msgs = in.read();
                if (msgs == null) {
                    // graceful disconnect
                    disconnectSwitch(key, sw);
                    return;
                }
                sw.setLastReceivedMessageTime(System.currentTimeMillis());
                handleMessages(sw, msgs);
            }

            if (key.isWritable()) {
                out.clearSelect();
                key.interestOps(SelectionKey.OP_READ);
            }

            if (out.getWriteFailure()) {
                disconnectSwitch(key, sw);
                return;
            }
        } catch (IOException e) {
            // if we have an exception, disconnect the switch
            disconnectSwitch(key, sw);
        } catch (CancelledKeyException e) {
            // if we have an exception, disconnect the switch
            disconnectSwitch(key, sw);
        }
    }

    /**
     * Disconnect the switch from Beacon
     */
    protected void disconnectSwitch(SelectionKey key, IOFSwitchExt sw) {
        key.cancel();
        OFStream stream = (OFStream) sw.getInputStream();
        stream.getIOLoop().removeStream(stream);
        removeSwitch(sw);
        try {
            sw.getSocketChannel().socket().close();
        } catch (IOException e1) {
        }
        log.info("Switch disconnected {}", sw);
    }

    /**
     * Handle replies to certain OFMessages, and pass others off to listeners
     * @param sw
     * @param msgs
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    protected void handleMessages(IOFSwitchExt sw, List<OFMessage> msgs)
            throws IOException {
        for (OFMessage m : msgs) {
            // If we detect a write failure, break early so we can disconnect
            if (((OFStream)sw.getInputStream()).getWriteFailure()) {
                break;
            }
            // Always handle ECHO REQUESTS, regardless of state
            switch (m.getType()) {
                case ECHO_REQUEST:
                    OFMessageInStream in = sw.getInputStream();
                    OFMessageOutStream out = sw.getOutputStream();
                    OFEchoReply reply = (OFEchoReply) in
                            .getMessageFactory().getMessage(
                                    OFType.ECHO_REPLY);
                    reply.setXid(m.getXid());
                    out.write(reply);
                    break;
                case ECHO_REPLY:
                    // *Note, ECHO REPLIES need no handling due to last message timestamp
                    break;
                case ERROR:
                    logError(sw, (OFError)m);
                    // fall through intentionally so error can be listened for
                default:
                    switch (sw.getState()) {
                        case HELLO_SENT:
                            if (m.getType() == OFType.HELLO) {
                                log.debug("HELLO from {}", sw);
                                sw.transitionToState(SwitchState.FEATURES_REQUEST_SENT);
                                // Send initial Features Request
                                sw.getOutputStream().write(factory.getMessage(OFType.FEATURES_REQUEST));
                            }
                            break;
                        case FEATURES_REQUEST_SENT:
                            if (m.getType() == OFType.FEATURES_REPLY) {
                                log.debug("Features Reply from {}", sw);
                                sw.setFeaturesReply((OFFeaturesReply) m);
                                sw.transitionToState(SwitchState.GET_CONFIG_REQUEST_SENT);
                                // Set config and request to receive the config
                                OFSetConfig config = (OFSetConfig) factory
                                        .getMessage(OFType.SET_CONFIG);
                                config.setMissSendLength((short) 0xffff)
                                .setLengthU(OFSetConfig.MINIMUM_LENGTH);
                                sw.getOutputStream().write(config);
                                sw.getOutputStream().write(factory.getMessage(OFType.BARRIER_REQUEST));
                                sw.getOutputStream().write(factory.getMessage(OFType.GET_CONFIG_REQUEST));
                            }
                            break;
                        case GET_CONFIG_REQUEST_SENT:
                            if (m.getType() == OFType.GET_CONFIG_REPLY) {
                                OFGetConfigReply cr = (OFGetConfigReply) m;
                                if (cr.getMissSendLength() == (short)0xffff) {
                                    log.debug("Config Reply from {} confirms miss length set to 0xffff", sw);
                                    sw.transitionToState(SwitchState.INITIALIZING);
                                    // Add all existing initializers to the list
                                    this.initializerMap.put(sw,
                                        (CopyOnWriteArrayList<IOFInitializerListener>) initializerList.clone());
                                    log.debug("Remaining initializers for switch {}: {}", sw, this.initializerMap.get(sw));

                                    // Delete all pre-existing flows
                                    if (deletePreExistingFlows) {
                                        OFMatch match = new OFMatch().setWildcards(OFMatch.OFPFW_ALL);
                                        OFMessage fm = ((OFFlowMod) sw.getInputStream().getMessageFactory()
                                                .getMessage(OFType.FLOW_MOD))
                                                .setMatch(match)
                                                .setCommand(OFFlowMod.OFPFC_DELETE)
                                                .setOutPort(OFPort.OFPP_NONE)
                                                .setLength(U16.t(OFFlowMod.MINIMUM_LENGTH));
                                        sw.getOutputStream().write(fm);
                                        sw.getOutputStream().write(factory.getMessage(OFType.BARRIER_REQUEST));
                                    }
                                } else {
                                    log.error("Switch {} refused to set miss send length to 0xffff, disconnecting", sw);
                                    disconnectSwitch(((OFStream)sw.getInputStream()).getKey(), sw);
                                    return;
                                }
                            }
                            break;
                        case INITIALIZING:
                            // Are there pending initializers for this switch?
                            CopyOnWriteArrayList<IOFInitializerListener> initializers =
                                initializerMap.get(sw);
                            if (initializers != null) {
                                Iterator<IOFInitializerListener> it = initializers.iterator();
                                if (it.hasNext()) {
                                    IOFInitializerListener listener = it.next();
                                    try {
                                        listener.initializerReceive(sw, m);
                                    } catch (Exception e) {
                                        log.error(
                                                "Error calling initializer listener: {} on switch: {} for message: {}, removing listener",
                                                new Object[] { listener, sw, m });
                                        initializers.remove(listener);
                                    }
                                }
                                if (initializers.size() == 0) {
                                    // no initializers remaining
                                    initializerMap.remove(sw);
                                    sw.transitionToState(SwitchState.ACTIVE);
                                    // Add switch to active list
                                    addActiveSwitch(sw);
                                }
                            } else {
                                sw.transitionToState(SwitchState.ACTIVE);
                                // Add switch to active list
                                addActiveSwitch(sw);
                            }
                            break;
                        case ACTIVE:
                            List<IOFMessageListener> listeners = messageListeners
                            .get(m.getType());
                            if (listeners != null) {
                                for (IOFMessageListener listener : listeners) {
                                    try {
                                        if (listener instanceof IOFSwitchFilter) {
                                            if (!((IOFSwitchFilter)listener).isInterested(sw)) {
                                                continue;
                                            }
                                        }
                                        if (Command.STOP.equals(listener.receive(sw, m))) {
                                            break;
                                        }
                                    } catch (Exception e) {
                                        log.error("Failure calling listener ["+
                                                listener.toString()+
                                                "] with message ["+m.toString()+
                                                "]", e);
                                    }
                                }
                            } else {
                                log.warn("Unhandled OF Message: {} from {}", m, sw);
                            }
                            break;
                    } // end switch(sw.getState())
            } // end switch(m.getType())
        }
    }

    protected void logError(IOFSwitch sw, OFError error) {
        // TODO Move this to OFJ with *much* better printing
        OFErrorType et = OFErrorType.values()[0xffff & error.getErrorType()];
        switch (et) {
            case OFPET_HELLO_FAILED:
                OFHelloFailedCode hfc = OFHelloFailedCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, hfc, sw});
                break;
            case OFPET_BAD_REQUEST:
                OFBadRequestCode brc = OFBadRequestCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, brc, sw});
                break;
            case OFPET_BAD_ACTION:
                OFBadActionCode bac = OFBadActionCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, bac, sw});
                break;
            case OFPET_FLOW_MOD_FAILED:
                OFFlowModFailedCode fmfc = OFFlowModFailedCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, fmfc, sw});
                break;
            case OFPET_PORT_MOD_FAILED:
                OFPortModFailedCode pmfc = OFPortModFailedCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, pmfc, sw});
                break;
            case OFPET_QUEUE_OP_FAILED:
                OFQueueOpFailedCode qofc = OFQueueOpFailedCode.values()[0xffff & error.getErrorCode()];
                log.error("Error {} {} from {}", new Object[] {et, qofc, sw});
                break;
            default:
                break;
        }
    }

    public synchronized void addOFMessageListener(OFType type, IOFMessageListener listener) {
        List<IOFMessageListener> listeners = messageListeners.get(type);
        if (listeners == null) {
            // Set atomically if no list exists
            messageListeners.putIfAbsent(type,
                    new CopyOnWriteArrayList<IOFMessageListener>());
            // Get the list, the new one or any other, guaranteed not null
            listeners = messageListeners.get(type);
        }

        if (callbackOrdering != null &&
                callbackOrdering.containsKey(type.toString()) &&
                callbackOrdering.get(type.toString()).contains(listener.getName())) {
            String order = callbackOrdering.get(type.toString());
            String[] orderArray = order.split(",");
            int myPos = 0;
            for (int i = 0; i < orderArray.length; ++i) {
                orderArray[i] = orderArray[i].trim();
                if (orderArray[i].equals(listener.getName()))
                    myPos = i;
            }
            List<String> beforeList = Arrays.asList(Arrays.copyOfRange(orderArray, 0, myPos));

            boolean added = false;
            // only try and walk if there are already listeners
            if (listeners.size() > 0) {
                // Walk through and determine where to insert
                for (int i = 0; i < listeners.size(); ++i) {
                    if (beforeList.contains(listeners.get(i).getName()))
                        continue;
                    listeners.add(i, listener);
                    added = true;
                    break;
                }
            }
            if (!added) {
                listeners.add(listener);
            }

        } else {
            listeners.add(listener);
        }
    }

    public synchronized void removeOFMessageListener(OFType type, IOFMessageListener listener) {
        List<IOFMessageListener> listeners = messageListeners.get(type);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    public void startUp() throws IOException {
        initializerList = new CopyOnWriteArrayList<IOFInitializerListener>();
        initializerMap = new ConcurrentHashMap<IOFSwitchExt, CopyOnWriteArrayList<IOFInitializerListener>>();
        switchIOLoops = new ArrayList<IOLoop>();
        activeSwitches = new ConcurrentHashMap<Long, IOFSwitchExt>();
        allSwitches = new CopyOnWriteArraySet<IOFSwitchExt>();

        if (threadCount == null)
            threadCount = 1;

        this.factory = new BasicFactory();

        // Static number of threads equal to processor cores (+1 for listen loop)
        es = Executors.newFixedThreadPool(threadCount+1);

        // Launch one select loop per threadCount and start running
        for (int i = 0; i < threadCount; ++i) {
            final IOLoop sl = new IOLoop(this, 500, i);
            switchIOLoops.add(sl);
            es.execute(new Runnable() {
                public void run() {
                    try {
                        log.info("Started thread {} for IOLoop {}", Thread.currentThread(), sl);
                        sl.doLoop();
                    } catch (Exception e) {
                        log.error("Exception during worker loop, terminating thread", e);
                    }
                }}
            );
        }

        updatesThread = new Thread(new Runnable () {
            @Override
            public void run() {
                while (true) {
                    try {
                        Update update = updates.take();
                        if (switchListeners != null) {
                            for (IOFSwitchListener listener : switchListeners) {
                                try {
                                    if (update.added)
                                        listener.addedSwitch(update.sw);
                                    else
                                        listener.removedSwitch(update.sw);
                                } catch (Exception e) {
                                    log.error("Error calling switch listener", e);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        log.warn("Controller updates thread interupted", e);
                        if (shuttingDown)
                            return;
                    }
                }
            }}, "Controller Updates");
        updatesThread.start();

        livenessTimer = new Timer();
        livenessTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkSwitchLiveness();
            }
        }, LIVENESS_POLL_INTERVAL, LIVENESS_POLL_INTERVAL);

        log.info("Beacon Core Started");
    }

    public synchronized void startListener() {
        if (listenerStarted)
            return;

        try {
            listenSock = ServerSocketChannel.open();
            listenSock.socket().setReceiveBufferSize(512*1024);
            listenSock.configureBlocking(false);
            if (listenAddress != null) {
                listenSock.socket().bind(
                        new java.net.InetSocketAddress(InetAddress
                                .getByAddress(IPv4
                                        .toIPv4AddressBytes(listenAddress)),
                                listenPort));
            } else {
                listenSock.socket().bind(new java.net.InetSocketAddress(listenPort));
            }
            listenSock.socket().setReuseAddress(true);

            listenerIOLoop = new IOLoop(this, -1);
            // register this connection for accepting
            listenerIOLoop.register(listenSock, SelectionKey.OP_ACCEPT, listenSock);
        } catch (IOException e) {
            log.error("Failure opening listening socket", e);
            System.exit(-1);
        }

        log.info("Controller listening on {}:{}", listenAddress == null ? "*"
                : listenAddress, listenPort);


        es.execute(new Runnable() {
            public void run() {
                // Start the listen loop
                try {
                    listenerIOLoop.doLoop();
                } catch (Exception e) {
                    log.error("Exception during accept loop, terminating thread", e);
                }
            }}
        );

        listenerStarted = true;
    }

    public synchronized void stopListener() {
        if (!listenerStarted)
            return;

        // shutdown listening for new switches
        try {
            listenerIOLoop.shutdown();
            listenSock.socket().close();
            listenSock.close();
        } catch (IOException e) {
            log.error("Failure shutting down listening socket", e);
        } finally {
            listenerStarted = false;
        }
    }

    public void shutDown() throws IOException {
        shuttingDown = true;
        livenessTimer.cancel();

        stopListener();

        // close the switch connections
        for (Iterator<Entry<Long, IOFSwitchExt>> it = activeSwitches.entrySet().iterator(); it.hasNext();) {
            Entry<Long, IOFSwitchExt> entry = it.next();
            entry.getValue().getSocketChannel().socket().close();
            it.remove();
        }

        // shutdown the connected switch select loops
        for (IOLoop sl : switchIOLoops) {
            sl.shutdown();
        }

        es.shutdown();
        updatesThread.interrupt();
        log.info("Beacon Core Shutdown");
    }

    /**
     * Checks all the switches to ensure they are still connected by sending
     * an echo request and receiving a response.
     */
    protected void checkSwitchLiveness() {
        long now = System.currentTimeMillis();
        log.trace("Liveness timer running");

        for (Iterator<IOFSwitchExt> it = allSwitches.iterator(); it.hasNext();) {
            IOFSwitchExt sw = it.next();
            long last = sw.getLastReceivedMessageTime();
            SelectionKey key = ((OFStream)sw.getInputStream()).getKey();

            if (now - last >= (2*LIVENESS_TIMEOUT)) {
                log.info("Switch liveness timeout detected {}ms, disconnecting {}", now - last, sw);
                disconnectSwitch(key, sw);
            } else if (now - last >= LIVENESS_TIMEOUT) {
                // send echo
                OFEchoRequest echo = new OFEchoRequest();
                try {
                    sw.getOutputStream().write(echo);
                } catch (IOException e) {
                    log.error("Failure sending liveness probe, disconnecting switch " + sw.toString(), e);
                    disconnectSwitch(key, sw);
                }
            }
        }
    }

    /**
     * @param callbackOrdering the callbackOrdering to set
     */
    public void setCallbackOrdering(Map<String, String> callbackOrdering) {
        this.callbackOrdering = callbackOrdering;
    }

    /**
     * @return the messageListeners
     */
    protected ConcurrentMap<OFType, List<IOFMessageListener>> getMessageListeners() {
        return messageListeners;
    }

    /**
     * @param messageListeners the messageListeners to set
     */
    protected void setMessageListeners(
            ConcurrentMap<OFType, List<IOFMessageListener>> messageListeners) {
        this.messageListeners = messageListeners;
    }

    @Override
    public Map<Long, IOFSwitch> getSwitches() {
        return Collections.unmodifiableMap(new HashMap<Long, IOFSwitch>(this.activeSwitches));
    }

    /**
     * This is only to be used for testing
     * @return
     */
    protected Set<IOFSwitchExt> getAllSwitches() {
        return Collections.unmodifiableSet(this.allSwitches);
    }

    @Override
    public void addOFSwitchListener(IOFSwitchListener listener) {
        this.switchListeners.add(listener);
    }

    @Override
    public void removeOFSwitchListener(IOFSwitchListener listener) {
        this.switchListeners.remove(listener);
    }

    /**
     * Adds a switch that has transitioned into the HELLO_SENT state
     *
     * @param sw the new switch
     */
    protected void addSwitch(IOFSwitchExt sw) {
        this.allSwitches.add(sw);
    }

    /**
     * Adds a switch that has transitioned into the ACTIVE state, then
     * calls all related listeners
     * @param sw the new switch
     */
    protected void addActiveSwitch(IOFSwitchExt sw) {
        this.activeSwitches.put(sw.getId(), sw);
        Update update = new Update(sw, true);
        try {
            this.updates.put(update);
        } catch (InterruptedException e) {
            log.error("Failure adding update to queue", e);
        }
    }

    /**
     * Removes a disconnected switch and calls all related listeners
     * @param sw the switch that has disconnected
     */
    protected void removeSwitch(IOFSwitchExt sw) {
        this.allSwitches.remove(sw);
        // If active remove from DPID indexed map
        if (SwitchState.ACTIVE == sw.getState()) {
            if (!this.activeSwitches.remove(sw.getId(), sw)) {
                log.warn("Removing switch {} has already been replaced", sw);
            }
            Update update = new Update(sw, false);
            try {
                this.updates.put(update);
            } catch (InterruptedException e) {
                log.error("Failure adding update to queue", e);
            }
        }
    }

    @Override
    public Map<OFType, List<IOFMessageListener>> getListeners() {
        return Collections.unmodifiableMap(this.messageListeners);
    }

    /**
     * @param listenAddress the listenAddress to set
     */
    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    /**
     * @param listenPort the listenPort to set
     */
    public void setListenPort(int listenPort) {
        this.listenPort = listenPort;
    }

    /**
     * @param threadCount the threadCount to set
     */
    public void setThreadCount(Integer threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * Configures all switch output streams to attempt to flush on every write
     * @param immediate the immediate to set
     */
    public void setImmediate(boolean immediate) {
        this.immediate = immediate;
    }

    /**
     * Used to set whether newly connected sockets have no delay turned on, 
     * defaults to true.
     * @param noDelay the noDelay to set
     */
    public void setNoDelay(boolean noDelay) {
        this.noDelay = noDelay;
    }

    /**
     * @param deletePreExistingFlows the deletePreExistingFlows to set
     */
    public void setDeletePreExistingFlows(boolean deletePreExistingFlows) {
        this.deletePreExistingFlows = deletePreExistingFlows;
    }

    @Override
    public void addOFInitializerListener(IOFInitializerListener listener) {
        this.initializerList.add(listener);
    }

    @Override
    public void removeOFInitListener(IOFInitializerListener listener) {
        this.initializerList.remove(listener);
        Iterator<Map.Entry<IOFSwitchExt, CopyOnWriteArrayList<IOFInitializerListener>>> it =
            this.initializerMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<IOFSwitchExt, CopyOnWriteArrayList<IOFInitializerListener>> entry =
                it.next();
            entry.getValue().remove(listener);
        }
    }

    @Override
    public void initializationComplete(IOFSwitch sw, IOFInitializerListener listener) {
        // TODO this cast isn't ideal.. is there a better alternative?
        log.debug("Initializer for switch {} has completed: {}", sw, listener);
        IOFSwitchExt swExt = (IOFSwitchExt) sw;
        CopyOnWriteArrayList<IOFInitializerListener> list = this.initializerMap.get(swExt);
        if (list != null) {
            list.remove(listener);
            log.debug("Remaining initializers for switch {}: {}", sw, list);
            if (list.isEmpty()) {
                this.initializerMap.remove(swExt);
                swExt.transitionToState(SwitchState.ACTIVE);
            }
        }
    }
}
