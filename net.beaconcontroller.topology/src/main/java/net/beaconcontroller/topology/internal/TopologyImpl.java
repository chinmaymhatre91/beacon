package net.beaconcontroller.topology.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Map.Entry;

import net.beaconcontroller.core.IBeaconProvider;
import net.beaconcontroller.core.IOFMessageListener;
import net.beaconcontroller.core.IOFSwitch;
import net.beaconcontroller.core.IOFSwitchListener;
import net.beaconcontroller.packet.Ethernet;
import net.beaconcontroller.packet.LLDP;
import net.beaconcontroller.packet.LLDPTLV;
import net.beaconcontroller.topology.ITopology;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFPacketOut;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFType;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author David Erickson (derickso@stanford.edu)
 */
public class TopologyImpl implements IOFMessageListener, IOFSwitchListener, ITopology {
    protected static Logger logger = LoggerFactory.getLogger(TopologyImpl.class);

    protected IBeaconProvider beaconProvider;
    protected Timer timer;

    protected void startUp() {
        beaconProvider.addOFMessageListener(OFType.PACKET_IN, this);
        beaconProvider.addOFSwitchListener(this);
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                sendLLDPs();
            }}, 1000, 60*1000);
    }

    protected void shutDown() {
        timer.cancel();
        beaconProvider.removeOFSwitchListener(this);
        beaconProvider.removeOFMessageListener(OFType.PACKET_IN, this);
    }

    protected void sendLLDPs() {
        Ethernet ethernet = new Ethernet()
            .setSourceMACAddress(new byte[6])
            .setDestinationMACAddress("01:80:c2:00:00:0e")
            .setEtherType(Ethernet.TYPE_LLDP);

        LLDP lldp = new LLDP();
        ethernet.setPayload(lldp);
        byte[] chassisId = new byte[] {4, 0, 0, 0, 0, 0, 0}; // filled in later
        byte[] portId = new byte[] {2, 0, 0}; // filled in later
        lldp.setChassisId(new LLDPTLV().setType((byte) 1).setLength((short) 7).setValue(chassisId));
        lldp.setPortId(new LLDPTLV().setType((byte) 2).setLength((short) 3).setValue(portId));
        lldp.setTtl(new LLDPTLV().setType((byte) 3).setLength((short) 2).setValue(new byte[] {0, 0x78}));

        // OpenFlow OUI - 00-26-E1
        byte[] dpidTLVValue = new byte[] {0x0, 0x26, (byte) 0xe1, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        LLDPTLV dpidTLV = new LLDPTLV().setType((byte) 0x127).setLength((short) 12).setValue(dpidTLVValue);
        lldp.setOptionalTLVList(new ArrayList<LLDPTLV>());
        lldp.getOptionalTLVList().add(dpidTLV);

        Map<Long, IOFSwitch> switches = beaconProvider.getSwitches();
        byte[] dpidArray = new byte[8];
        ByteBuffer dpidBB = ByteBuffer.wrap(dpidArray);
        ByteBuffer portBB = ByteBuffer.wrap(portId, 1, 2);
        for (Entry<Long, IOFSwitch> entry : switches.entrySet()) {
            Long dpid = entry.getKey();
            IOFSwitch sw = entry.getValue();
            dpidBB.putLong(dpid);

            // set the ethernet source mac to last 6 bytes of dpid
            System.arraycopy(dpidArray, 2, ethernet.getSourceMACAddress(), 0, 6);
            // set the chassis id's value to last 6 bytes of dpid
            System.arraycopy(dpidArray, 2, chassisId, 1, 6);
            // set the optional tlv to the full dpid
            System.arraycopy(dpidArray, 0, dpidTLVValue, 4, 8);
            for (OFPhysicalPort port : sw.getFeaturesReply().getPorts()) {
                if (port.getPortNumber() == OFPort.OFPP_LOCAL.getValue())
                    continue;

                // set the portId to the outgoing port
                portBB.putShort(port.getPortNumber());

                // serialize and wrap in a packet out
                byte[] data = ethernet.serialize();
                OFPacketOut po = new OFPacketOut();
                po.setBufferId(OFPacketOut.BUFFER_ID_NONE);
                po.setInPort(OFPort.OFPP_NONE);

                // set actions
                List<OFAction> actions = new ArrayList<OFAction>();
                actions.add(new OFActionOutput(port.getPortNumber(), (short) 0));
                po.setActions(actions);
                po.setActionsLength((short) OFActionOutput.MINIMUM_LENGTH);

                // set data
                po.setLengthU(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength() + data.length);
                po.setPacketData(data);

                // send
                try {
                    sw.getOutputStream().write(po);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // rewind for next pass
                portBB.position(1);
            }

            // rewind for next pass
            dpidBB.position(0);
        }
    }

    @Override
    public String getName() {
        return "topology";
    }

    @Override
    public Command receive(IOFSwitch sw, OFMessage msg) {
        OFPacketIn pi = (OFPacketIn) msg;
        Ethernet eth = new Ethernet();
        eth.deserialize(pi.getPacketData(), 0, pi.getPacketData().length);

        if (!(eth.getPayload() instanceof LLDP))
            return Command.CONTINUE;

        // received by switch sw
        // received on port pi.getInPort()
        LLDP lldp = (LLDP) eth.getPayload();
        ByteBuffer portBB = ByteBuffer.wrap(lldp.getPortId().getValue());
        portBB.position(1);
        short remotePort = portBB.getShort();
        long remoteDpid = 0;
        boolean remoteDpidSet = false;

        for (LLDPTLV lldptlv : lldp.getOptionalTLVList()) {
            if (lldptlv.getType() == 0x127 && lldptlv.getLength() == 12 &&
                    lldptlv.getValue()[0] == 0x0 && lldptlv.getValue()[1] == 0x26 &&
                    lldptlv.getValue()[2] == 0xe1 && lldptlv.getValue()[3] == 0x0) {
                ByteBuffer dpidBB = ByteBuffer.wrap(lldptlv.getValue());
                remoteDpid = dpidBB.getLong(4);
                remoteDpidSet = true;
                break;
            }
        }

        if (!remoteDpidSet) {
            logger.error("Failed to determine remote switch DPID from received LLDP");
            return Command.STOP;
        }

        IOFSwitch remoteSwitch = beaconProvider.getSwitches().get(remoteDpid);

        if (remoteSwitch == null) {
            logger.error("Failed to locate remote switch with DPID: {}", remoteDpid);
            return Command.STOP;
        }

        return Command.STOP;
    }

    @Override
    public void addedSwitch(IOFSwitch sw) {
    }

    @Override
    public void removedSwitch(IOFSwitch sw) {
    }

    /**
     * @param beaconProvider the beaconProvider to set
     */
    public void setBeaconProvider(IBeaconProvider beaconProvider) {
        this.beaconProvider = beaconProvider;
    }
}