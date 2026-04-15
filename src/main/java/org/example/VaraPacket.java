package org.example;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * VaraPacket represents a single PDU in the custom RF mesh networking protocol over VARA KISS TCP.
 *
 * VXP v1.0 Header (17 bytes):
 * 0: protocolSignature = 0x58 ('X')
 * 1: version = 0x01
 * 2: packetType (see PacketType enum)
 * 3-8: sourceCallsign (6 ASCII chars, padded/truncated)
 * 9-14: destCallsign (6 ASCII chars, padded/truncated)
 * 15: ttl (0-3)
 * 16: payloadLength (unsigned byte, max 238)
 *
 * Bytes 17+: payload (length indicated by payloadLength)
 */
public class VaraPacket {
    public static final byte CURRENT_PROTOCOL_VERSION = 0x01;
    private static final byte PROTOCOL_SIGNATURE = 0x58; // 'X'
    private static final int HEADER_SIZE = 17;
    private static final int CALLSIGN_LEN = 6;
    private static final Charset ASCII = StandardCharsets.US_ASCII;

    public enum PacketType {
        BEACON(1),
        MSG(2),
        FILE_META(3),
        FILE_CHUNK(4),
        ACK(5),
        RELAY_REQ(6),
        QSY_REQ(7),
        QSY_ACK(8);

        private final int code;
        PacketType(int code) { this.code = code; }
        public int getCode() { return code; }
        public static PacketType fromCode(int code) {
            for (PacketType t : values()) {
                if (t.code == code) return t;
            }
            throw new IllegalArgumentException("Unknown packet type code: " + code);
        }
    }

    private final byte protocolVersion;
    private final PacketType packetType;
    private final String sourceCallsign;  // exactly 6 ASCII when serialized
    private final String destCallsign;    // exactly 6 ASCII when serialized
    private final byte ttl;               // 0-3
    private final byte[] payload;         // 0-238 bytes (VXP v1.0)

    public VaraPacket(PacketType packetType, String sourceCallsign, String destCallsign, int ttl, byte[] payload) {
        this(CURRENT_PROTOCOL_VERSION, packetType, sourceCallsign, destCallsign, ttl, payload);
    }

    public VaraPacket(byte protocolVersion, PacketType packetType, String sourceCallsign, String destCallsign, int ttl, byte[] payload) {
        this.protocolVersion = protocolVersion;
        this.packetType = Objects.requireNonNull(packetType, "packetType");
        this.sourceCallsign = normalizeCallsign(sourceCallsign);
        this.destCallsign = normalizeCallsign(destCallsign);
        if (ttl < 0 || ttl > 3) {
            throw new IllegalArgumentException("ttl must be 0..3, got " + ttl);
        }
        this.ttl = (byte) ttl;
        this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if ((this.payload.length & 0xFF) != this.payload.length || this.payload.length > 238) {
            throw new IllegalArgumentException("payload length must be 0..238, got " + this.payload.length);
        }
    }

    public byte[] toBytes() {
        int totalLen = HEADER_SIZE + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen);
        // Header (VXP v1.0)
        buf.put(PROTOCOL_SIGNATURE);
        buf.put(protocolVersion);
        buf.put((byte) packetType.getCode());
        buf.put(encodeCallsign(sourceCallsign));
        buf.put(encodeCallsign(destCallsign));
        buf.put(ttl);
        buf.put((byte) (payload.length & 0xFF));
        // Payload
        buf.put(payload);
        return buf.array();
    }

    public static VaraPacket fromBytes(byte[] rawData) {
        if (rawData == null || rawData.length < HEADER_SIZE) {
            // Too short for VXP v1.0 header; discard
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(rawData);
        byte signature = buf.get();
        if (signature != PROTOCOL_SIGNATURE) {
            // Not a VXP packet (may be other KISS traffic) — silently discard
            return null;
        }
        byte version = buf.get();
        if ((version & 0xFF) > (CURRENT_PROTOCOL_VERSION & 0xFF)) {
            System.err.println("[VXP] Received VXP packet from future version");
        }
        int typeCode = buf.get() & 0xFF;
        PacketType type = PacketType.fromCode(typeCode);
        byte[] srcBytes = new byte[CALLSIGN_LEN];
        byte[] dstBytes = new byte[CALLSIGN_LEN];
        buf.get(srcBytes);
        buf.get(dstBytes);
        String source = new String(srcBytes, ASCII);
        String dest = new String(dstBytes, ASCII);
        int ttl = buf.get() & 0xFF;
        if (ttl < 0 || ttl > 3) {
            // Invalid TTL — discard
            return null;
        }
        int payloadLen = buf.get() & 0xFF;
        if (payloadLen > 238) {
            // Exceeds VXP v1.0 max payload — discard
            return null;
        }
        int remaining = rawData.length - HEADER_SIZE;
        if (remaining < payloadLen) {
            // Truncated frame — discard
            return null;
        }
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            buf.get(payload, 0, payloadLen);
        }
        return new VaraPacket(version, type, source, dest, ttl, payload);
    }

    private static String normalizeCallsign(String s) {
        if (s == null) s = "";
        // Ensure ASCII-safe and pad/truncate to 6 characters, use spaces for padding
        String ascii = new String(s.getBytes(ASCII), ASCII);
        if (ascii.length() > CALLSIGN_LEN) {
            return ascii.substring(0, CALLSIGN_LEN);
        }
        StringBuilder sb = new StringBuilder(CALLSIGN_LEN);
        sb.append(ascii);
        while (sb.length() < CALLSIGN_LEN) sb.append(' ');
        return sb.toString();
    }

    private static byte[] encodeCallsign(String s) {
        String fixed = normalizeCallsign(s);
        byte[] b = fixed.getBytes(ASCII);
        if (b.length == CALLSIGN_LEN) return b;
        return Arrays.copyOf(b, CALLSIGN_LEN);
    }

    public byte getProtocolVersion() { return protocolVersion; }
    public PacketType getPacketType() { return packetType; }
    public String getSourceCallsign() { return sourceCallsign; }
    public String getDestCallsign() { return destCallsign; }
    public int getTtl() { return ttl & 0xFF; }
    public byte[] getPayload() { return Arrays.copyOf(payload, payload.length); }

    @Override
    public String toString() {
        return "VaraPacket{" +
                "ver=" + (protocolVersion & 0xFF) +
                ", type=" + packetType +
                ", src='" + sourceCallsign + '\'' +
                ", dst='" + destCallsign + '\'' +
                ", ttl=" + (ttl & 0xFF) +
                ", payloadLen=" + payload.length +
                '}';
    }
}