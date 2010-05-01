package net.beaconcontroller.packet;

import java.nio.ByteBuffer;

/**
 *
 * @author David Erickson (derickso@stanford.edu)
 */
public class UDP extends BasePacket {
    protected short sourcePort;
    protected short destinationPort;
    protected short length;
    protected short checksum;

    /**
     * @return the sourcePort
     */
    public short getSourcePort() {
        return sourcePort;
    }

    /**
     * @param sourcePort the sourcePort to set
     */
    public UDP setSourcePort(short sourcePort) {
        this.sourcePort = sourcePort;
        return this;
    }

    /**
     * @return the destinationPort
     */
    public short getDestinationPort() {
        return destinationPort;
    }

    /**
     * @param destinationPort the destinationPort to set
     */
    public UDP setDestinationPort(short destinationPort) {
        this.destinationPort = destinationPort;
        return this;
    }

    /**
     * @return the length
     */
    public short getLength() {
        return length;
    }

    /**
     * @param length the length to set
     */
    public UDP setLength(short length) {
        this.length = length;
        return this;
    }

    /**
     * @return the checksum
     */
    public short getChecksum() {
        return checksum;
    }

    /**
     * @param checksum the checksum to set
     */
    public UDP setChecksum(short checksum) {
        this.checksum = checksum;
        return this;
    }

    /**
     * Serializes the packet. Will compute and set the following fields if they
     * are set to specific values at the time serialize is called:
     *      -checksum : 0
     *      -length : 0
     */
    public byte[] serialize() {
        byte[] payloadData = null;
        if (payload != null) {
            payload.setParent(this);
            payloadData = payload.serialize();
        }

        if (this.length == 0) {
            this.length = (short) (8 + ((payloadData == null) ? 0
                    : payloadData.length));
        }

        byte[] data = new byte[this.length];
        ByteBuffer bb = ByteBuffer.wrap(data);

        bb.putShort(this.sourcePort);
        bb.putShort(this.destinationPort);
        bb.putShort(this.length);
        bb.putShort(this.checksum);
        if (payloadData != null)
            bb.put(payloadData);

        if (this.parent != null && this.parent instanceof IPv4)
            ((IPv4)this.parent).setProtocol(IPv4.PROTOCOL_UDP);

        // compute checksum if needed
        if (this.checksum == 0) {
            bb.rewind();
            int accumulation = 0;

            // compute pseudo header mac
            if (this.parent != null && this.parent instanceof IPv4) {
                IPv4 ipv4 = (IPv4) this.parent;
                accumulation += ((ipv4.getSourceAddress() >> 16) & 0xffff)
                        + (ipv4.getSourceAddress() & 0xffff);
                accumulation += ((ipv4.getDestinationAddress() >> 16) & 0xffff)
                        + (ipv4.getDestinationAddress() & 0xffff);
                accumulation += ipv4.getProtocol() & 0xff;
                accumulation += this.length & 0xffff;
            }

            for (int i = 0; i < this.length / 2; ++i) {
                accumulation += 0xffff & bb.getShort();
            }
            // pad to an even number of shorts
            if (this.length % 2 > 0) {
                accumulation += (bb.get() & 0xff) << 8;
            }

            accumulation = ((accumulation >> 16) & 0xffff)
                    + (accumulation & 0xffff);
            this.checksum = (short) (~accumulation & 0xffff);
            bb.putShort(6, this.checksum);
        }
        return data;
    }
}
