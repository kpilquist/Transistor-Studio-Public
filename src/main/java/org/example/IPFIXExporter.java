package org.example;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal IPFIX exporter tailored for PSK Reporter enterprise IEs (PEN 30351).
 * Sends a template and data records over UDP.
 */
public class IPFIXExporter {
    public static final int PSK_REPORTER_PEN = 30351;

    private final String host;
    private final int port;
    private final DatagramSocket socket;
    private final InetAddress address;
    private final int observationDomainId = 1;
    private final AtomicInteger sequenceNumber = new AtomicInteger(0);
    private volatile boolean templateSent = false;

    // Template ID for our PSKReporter record
    private static final int TEMPLATE_ID = 256;

    public IPFIXExporter(String host, int port) throws IOException {
        this.host = host;
        this.port = port;
        this.socket = new DatagramSocket();
        this.address = InetAddress.getByName(host);
    }

    public void close() {
        try {
            socket.close();
        } catch (Exception ignored) {}
    }

    /**
     * Send a single PSK Reporter data record. Automatically sends the template first if needed.
     */
    public synchronized void sendRecord(PSKReport report) {
        try {
            if (!templateSent) {
                byte[] template = buildTemplateMessage();
                send(template);
                templateSent = true;
            }
            byte[] data = buildDataMessage(report);
            send(data);
        } catch (Exception e) {
            LogBuffer.error("IPFIXExporter.sendRecord failed (" + host + ":" + port + ")", e);
        }
    }

    private void send(byte[] bytes) throws IOException {
        DatagramPacket pkt = new DatagramPacket(bytes, bytes.length, address, port);
        socket.send(pkt);
    }

    private byte[] buildTemplateMessage() {
        // Build a template set defining the fields we will export.
        ByteBuffer setBuf = ByteBuffer.allocate(2048).order(ByteOrder.BIG_ENDIAN);
        // Template Set header: Set ID = 2, Length to be filled after
        int setStart = setBuf.position();
        setBuf.putShort((short) 2); // Set ID = 2 (Template Set)
        setBuf.putShort((short) 0); // placeholder length

        // Template Record
        setBuf.putShort((short) TEMPLATE_ID); // Template ID
        // Field Count (number of fields in this template)
        int fieldCountPos = setBuf.position();
        setBuf.putShort((short) 0); // placeholder field count
        int fieldCount = 0;

        // Helper to add IEs
        fieldCount += addEnterpriseStringIE(setBuf, 1); // senderCallsign 30351.1
        fieldCount += addEnterpriseStringIE(setBuf, 2); // receiverCallsign 30351.2
        fieldCount += addEnterpriseStringIE(setBuf, 3); // senderLocator 30351.3 (optional)
        fieldCount += addEnterpriseStringIE(setBuf, 4); // receiverLocator 30351.4
        fieldCount += addEnterpriseUnsignedIE(setBuf, 5, 4); // frequency 30351.5 u32
        fieldCount += addEnterpriseSignedIE(setBuf, 6, 1); // sNR 30351.6 i8
        fieldCount += addEnterpriseSignedIE(setBuf, 7, 1); // iMD 30351.7 i8
        fieldCount += addEnterpriseStringIE(setBuf, 8); // decoderSoftware 30351.8
        fieldCount += addEnterpriseStringIE(setBuf, 9); // antennaInformation 30351.9
        fieldCount += addEnterpriseStringIE(setBuf, 10); // mode 30351.10
        fieldCount += addEnterpriseSignedIE(setBuf, 11, 1); // informationSource 30351.11 i8
        fieldCount += addEnterpriseStringIE(setBuf, 12); // persistentIdentifier 30351.12
        // Standard IE: flowStartSeconds (ID=150), 4 bytes unsigned seconds since epoch
        fieldCount += addStandardIE(setBuf, 150, 4);
        fieldCount += addEnterpriseStringIE(setBuf, 13); // rigInformation 30351.13
        fieldCount += addEnterpriseBytesIE(setBuf, 14); // messageBits 30351.14 (variable)
        fieldCount += addEnterpriseSignedIE(setBuf, 15, 2); // deltaT 30351.15 int16

        // Patch field count
        int curPos = setBuf.position();
        setBuf.putShort(fieldCountPos, (short) fieldCount);

        // Patch set length
        int setLength = curPos - setStart;
        setBuf.putShort(setStart + 2, (short) setLength);
        setBuf.position(curPos);

        // Wrap with IPFIX message header
        return wrapAsMessage(setBuf, 0 /*exportTime auto*/);
    }

    private byte[] buildDataMessage(PSKReport r) {
        ByteBuffer setBuf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
        // Data Set header: Set ID = TEMPLATE_ID, Length placeholder
        int setStart = setBuf.position();
        setBuf.putShort((short) TEMPLATE_ID);
        setBuf.putShort((short) 0); // length

        // Build a single Data Record following template order
        putVarString(setBuf, r.senderCallsign);
        putVarString(setBuf, r.receiverCallsign);
        putVarString(setBuf, r.senderLocator);
        putVarString(setBuf, r.receiverLocator);
        setBuf.putInt((int) r.frequencyHz);
        setBuf.put((byte) r.snrDb);
        setBuf.put((byte) r.imdDb);
        putVarString(setBuf, r.decoderSoftware);
        putVarString(setBuf, r.antennaInformation);
        putVarString(setBuf, r.mode);
        setBuf.put((byte) r.informationSource);
        putVarString(setBuf, r.persistentIdentifier);
        setBuf.putInt((int) r.flowStartSeconds);
        putVarString(setBuf, r.rigInformation);
        putVarBytes(setBuf, r.messageBits);
        setBuf.putShort((short) r.deltaTMillis);

        int curPos = setBuf.position();
        int setLength = curPos - setStart;
        setBuf.putShort(setStart + 2, (short) setLength);
        setBuf.position(curPos);

        return wrapAsMessage(setBuf, r.exportTimeSeconds);
    }

    private byte[] wrapAsMessage(ByteBuffer setsBuf, long exportTimeSeconds) {
        byte[] sets = new byte[setsBuf.position()];
        setsBuf.rewind();
        setsBuf.get(sets);

        int messageLen = 16 + sets.length;
        ByteBuffer msg = ByteBuffer.allocate(messageLen).order(ByteOrder.BIG_ENDIAN);
        msg.putShort((short) 0x000a); // IPFIX version 10
        msg.putShort((short) messageLen);
        long exportTime = exportTimeSeconds > 0 ? exportTimeSeconds : Instant.now().getEpochSecond();
        msg.putInt((int) exportTime);
        msg.putInt(sequenceNumber.getAndAdd(1));
        msg.putInt(observationDomainId);
        msg.put(sets);
        return msg.array();
    }

    private int addStandardIE(ByteBuffer b, int ieId, int length) {
        b.putShort((short) ieId);
        b.putShort((short) length);
        return 1;
    }

    private int addEnterpriseStringIE(ByteBuffer b, int enterpriseElementId) {
        // Set enterprise bit (0x8000) in the IE ID field and include enterprise number
        b.putShort((short) (0x8000 | enterpriseElementId));
        b.putShort((short) 65535); // variable length
        b.putInt(PSK_REPORTER_PEN);
        return 1;
    }

    private int addEnterpriseBytesIE(ByteBuffer b, int enterpriseElementId) {
        b.putShort((short) (0x8000 | enterpriseElementId));
        b.putShort((short) 65535); // variable length
        b.putInt(PSK_REPORTER_PEN);
        return 1;
    }

    private int addEnterpriseUnsignedIE(ByteBuffer b, int enterpriseElementId, int length) {
        b.putShort((short) (0x8000 | enterpriseElementId));
        b.putShort((short) length);
        b.putInt(PSK_REPORTER_PEN);
        return 1;
    }

    private int addEnterpriseSignedIE(ByteBuffer b, int enterpriseElementId, int length) {
        b.putShort((short) (0x8000 | enterpriseElementId));
        b.putShort((short) length);
        b.putInt(PSK_REPORTER_PEN);
        return 1;
    }

    private void putVarString(ByteBuffer b, String s) {
        if (s == null) s = "";
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        putVarLength(b, data.length);
        b.put(data);
    }

    private void putVarBytes(ByteBuffer b, byte[] data) {
        if (data == null) data = new byte[0];
        putVarLength(b, data.length);
        b.put(data);
    }

    // IPFIX variable-length encoding: 1 byte length if < 255 else 255 + 2-byte length
    private void putVarLength(ByteBuffer b, int len) {
        if (len < 255) {
            b.put((byte) len);
        } else {
            b.put((byte) 255);
            b.putShort((short) len);
        }
    }

    public static class PSKReport {
        public String senderCallsign;
        public String receiverCallsign;
        public String senderLocator;
        public String receiverLocator;
        public long frequencyHz;
        public int snrDb; // normally 1 byte
        public int imdDb; // normally 1 byte
        public String decoderSoftware;
        public String antennaInformation;
        public String mode;
        public int informationSource; // lower 2 bits per spec; 0x80 bit for test
        public String persistentIdentifier;
        public long flowStartSeconds; // epoch seconds
        public String rigInformation;
        public byte[] messageBits;
        public int deltaTMillis; // int16; 0x8000 for unknown
        public long exportTimeSeconds; // for header; optional
    }
}
