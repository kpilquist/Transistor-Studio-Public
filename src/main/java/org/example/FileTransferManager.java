package org.example;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * Handles outgoing file slicing into VaraPacket FILE_* PDUs and incoming reassembly of FILE_CHUNKs.
 *
 * VXP v1.0 protocol constraints:
 * - VaraPacket payload length is 1 byte (max 238) due to 17-byte header.
 * - FILE_CHUNK payload layout: [2-byte transferId][4-byte chunkIndex][up to 232-byte data].
 * - FILE_META payload is UTF-8 JSON: {"id":123, "name":"file.bin", "size":50000, "chunks":201}.
 * - Incoming map key: sourceCallsign + ":" + transferId (sourceCallsign trimmed of spaces from the PDU field).
 * - Completed files are saved under ./downloads/ with numeric suffixing if the name exists.
 */
public class FileTransferManager {
    public static final int CHUNK_DATA_SIZE = 232; // bytes of actual file data per FILE_CHUNK (238 - 6)
    private static final int FILE_CHUNK_HEADER_LEN = 2 + 4; // transferId (2) + chunkIndex (4)
    private static final int VARA_MAX_PAYLOAD = 238; // enforced by VaraPacket

    private final ConcurrentMap<String, IncomingFile> incoming = new ConcurrentHashMap<>();
    private final Consumer<File> fileCompletionListener;
    private final Path downloadsDir;
    private final Random idRng = new Random();

    public FileTransferManager(Consumer<File> fileCompletionListener) {
        this(fileCompletionListener, Paths.get("downloads"));
    }

    public FileTransferManager(Consumer<File> fileCompletionListener, Path downloadsDir) {
        this.fileCompletionListener = fileCompletionListener != null ? fileCompletionListener : f -> {};
        this.downloadsDir = downloadsDir != null ? downloadsDir : Paths.get("downloads");
        try {
            Files.createDirectories(this.downloadsDir);
        } catch (IOException e) {
            System.err.println("[FileTransferManager] Failed to create downloads directory: " + this.downloadsDir + ": " + e);
        }
    }

    /**
     * Slice the provided file into ordered VaraPacket list: a FILE_META followed by FILE_CHUNKs.
     * - transferId is a 16-bit value generated per transfer.
     * - TTL for FILE_* packets defaults to 1.
     */
    public List<VaraPacket> sliceFileForTransfer(File file, String sourceCall, String destCall) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!file.isFile()) throw new IOException("Not a regular file: " + file);
        String filename = file.getName();
        long fileSize = file.length();
        int totalChunks = (int) ((fileSize + CHUNK_DATA_SIZE - 1) / CHUNK_DATA_SIZE);
        int transferId = nextTransferId(); // 0..65535

        // Build FILE_META JSON
        String metaJson = buildMetaJson(transferId, filename, fileSize, totalChunks);
        byte[] metaPayload = metaJson.getBytes(StandardCharsets.UTF_8);
        if (metaPayload.length > VARA_MAX_PAYLOAD) {
            throw new IOException("FILE_META payload exceeds 238 bytes: " + metaPayload.length);
        }
        List<VaraPacket> packets = new ArrayList<>(totalChunks + 1);
        packets.add(new VaraPacket(
                VaraPacket.PacketType.FILE_META,
                sourceCall,
                destCall,
                1,
                metaPayload
        ));

        // Slice file into chunks
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] dataBuf = new byte[CHUNK_DATA_SIZE];
            int index = 0;
            while (true) {
                int read = in.read(dataBuf);
                if (read == -1) break;
                // Build FILE_CHUNK payload: [2 bytes id][4 bytes idx][read bytes data]
                int payloadLen = FILE_CHUNK_HEADER_LEN + read;
                if (payloadLen > VARA_MAX_PAYLOAD) {
                    // Should not happen because read <= CHUNK_DATA_SIZE
                    throw new IOException("Chunk payload exceeds max: " + payloadLen);
                }
                ByteBuffer buf = ByteBuffer.allocate(payloadLen);
                buf.putShort((short) (transferId & 0xFFFF));
                buf.putInt(index);
                buf.put(dataBuf, 0, read);
                packets.add(new VaraPacket(
                        VaraPacket.PacketType.FILE_CHUNK,
                        sourceCall,
                        destCall,
                        1,
                        buf.array()
                ));
                index++;
            }
        }
        return packets;
    }

    /**
     * Process an incoming VaraPacket that might be related to file transfer. Handles FILE_META and FILE_CHUNK.
     */
    public void processIncomingPacket(VaraPacket packet) {
        if (packet == null) return;
        VaraPacket.PacketType type = packet.getPacketType();
        switch (type) {
            case FILE_META:
                handleFileMeta(packet);
                break;
            case FILE_CHUNK:
                handleFileChunk(packet);
                break;
            default:
                // ignore other types
                break;
        }
    }

    private void handleFileMeta(VaraPacket packet) {
        String source = safeTrim(packet.getSourceCallsign());
        String json = new String(packet.getPayload(), StandardCharsets.UTF_8);
        try {
            Meta m = parseMetaJson(json);
            String key = buildKey(source, m.id);
            incoming.compute(key, (k, existing) -> {
                if (existing == null) {
                    return new IncomingFile(m.id, m.name, m.size, m.chunks, source);
                } else {
                    // If meta repeats, refresh expected values if sensible; keep received chunks
                    existing.updateMeta(m.name, m.size, m.chunks);
                    return existing;
                }
            });
            System.out.println("[FileTransferManager] FILE_META received: id=" + m.id + ", name=" + m.name + ", size=" + m.size + ", chunks=" + m.chunks + ", from=" + source);
        } catch (Exception ex) {
            System.err.println("[FileTransferManager] Failed to parse FILE_META from " + source + ": " + ex + ". JSON=\n" + json);
        }
    }

    private void handleFileChunk(VaraPacket packet) {
        byte[] p = packet.getPayload();
        if (p.length < FILE_CHUNK_HEADER_LEN) {
            System.err.println("[FileTransferManager] FILE_CHUNK too short: " + p.length);
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(p);
        int transferId = Short.toUnsignedInt(buf.getShort());
        int index = buf.getInt();
        int dataLen = p.length - FILE_CHUNK_HEADER_LEN;
        byte[] data = new byte[dataLen];
        if (dataLen > 0) buf.get(data);
        String source = safeTrim(packet.getSourceCallsign());
        String key = buildKey(source, transferId);
        IncomingFile inFile = incoming.get(key);
        if (inFile == null) {
            System.err.println("[FileTransferManager] Received FILE_CHUNK for unknown transfer: key=" + key + ", idx=" + index);
            return;
        }
        boolean completedNow = false;
        try {
            inFile.addChunk(index, data);
            if (inFile.isComplete()) {
                File written = writeOutFile(inFile);
                incoming.remove(key);
                System.out.println("[FileTransferManager] File complete: " + written.getAbsolutePath());
                completedNow = true;
                // Fire callback after file is fully written and closed
                try { fileCompletionListener.accept(written); } catch (Throwable cbErr) {
                    System.err.println("[FileTransferManager] Listener error: " + cbErr);
                }
            }
        } catch (Exception e) {
            System.err.println("[FileTransferManager] Error handling chunk idx=" + index + " for key=" + key + ": " + e);
        }
    }

    private File writeOutFile(IncomingFile inFile) throws IOException {
        // Resolve path with numeric suffixing
        Path out = resolveNonClobberingPath(downloadsDir.resolve(inFile.name));
        try (BufferedOutputStream outStream = new BufferedOutputStream(new FileOutputStream(out.toFile()))) {
            int expected = inFile.chunks;
            for (int i = 0; i < expected; i++) {
                byte[] part = inFile.getChunk(i);
                if (part == null) {
                    throw new IOException("Missing chunk index " + i + " during write-out.");
                }
                outStream.write(part);
            }
        }
        return out.toFile();
    }

    private static Path resolveNonClobberingPath(Path desired) {
        if (!Files.exists(desired)) return desired;
        String name = desired.getFileName().toString();
        Path parent = desired.getParent();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0) ? name.substring(0, dot) : name;
        String ext = (dot > 0) ? name.substring(dot) : "";
        int i = 1;
        while (true) {
            Path candidate = parent.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
            i++;
        }
    }

    private String buildKey(String sourceTrimmed, int transferId) {
        return sourceTrimmed + ":" + transferId;
    }

    private static String safeTrim(String s) {
        if (s == null) return "";
        return s.trim();
    }

    private String buildMetaJson(int id, String name, long size, int chunks) {
        // Compact JSON without escaping special chars in name for simplicity
        // If necessary, escape quotes and backslashes
        String safeName = name.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{" +
                "\"id\":" + id + "," +
                "\"name\":\"" + safeName + "\"," +
                "\"size\":" + size + "," +
                "\"chunks\":" + chunks +
                "}";
    }

    private Meta parseMetaJson(String json) {
        // Very small, permissive parser tailored to the expected format
        // Extract numbers and name by simple indexOf scanning.
        int id = (int) extractLong(json, "\"id\"");
        String name = extractString(json, "\"name\"");
        long size = extractLong(json, "\"size\"");
        int chunks = (int) extractLong(json, "\"chunks\"");
        if (id < 0 || id > 0xFFFF) throw new IllegalArgumentException("id out of range: " + id);
        if (chunks < 0) throw new IllegalArgumentException("chunks negative: " + chunks);
        return new Meta(id, name, size, chunks);
    }

    private static long extractLong(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("Missing key: " + key);
        int colon = json.indexOf(':', k + key.length());
        if (colon < 0) throw new IllegalArgumentException("Missing ':' after key: " + key);
        int i = colon + 1;
        // skip spaces
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int j = i;
        while (j < json.length() && (Character.isDigit(json.charAt(j)))) j++;
        if (j == i) throw new IllegalArgumentException("Expected number for key: " + key);
        return Long.parseLong(json.substring(i, j));
    }

    private static String extractString(String json, String key) {
        int k = json.indexOf(key);
        if (k < 0) throw new IllegalArgumentException("Missing key: " + key);
        int colon = json.indexOf(':', k + key.length());
        if (colon < 0) throw new IllegalArgumentException("Missing ':' after key: " + key);
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) throw new IllegalArgumentException("Missing opening quote for key: " + key);
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = q1 + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) {
                // simplistic unescape for \\" and \\\n                if (c == '"' || c == '\\') sb.append(c); else sb.append(c);
                esc = false;
            } else {
                if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
        }
        throw new IllegalArgumentException("Unterminated string for key: " + key);
    }

    private int nextTransferId() {
        // Return 0..65535
        return idRng.nextInt(0x10000);
    }

    private static final class Meta {
        final int id;
        final String name;
        final long size;
        final int chunks;
        Meta(int id, String name, long size, int chunks) {
            this.id = id; this.name = name; this.size = size; this.chunks = chunks;
        }
    }

    /**
     * Represents an in-progress incoming file assembly.
     */
    private static final class IncomingFile {
        final int id;
        volatile String name;
        volatile long size;
        volatile int chunks;
        final String sourceCall;
        final TreeMap<Integer, byte[]> parts = new TreeMap<>();

        IncomingFile(int id, String name, long size, int chunks, String sourceCall) {
            this.id = id; this.name = name; this.size = size; this.chunks = chunks; this.sourceCall = sourceCall;
        }

        synchronized void updateMeta(String name, long size, int chunks) {
            this.name = name; this.size = size; this.chunks = chunks;
        }

        synchronized void addChunk(int index, byte[] data) {
            if (index < 0) return;
            if (data == null) data = new byte[0];
            // store if not present to allow idempotent re-sends
            parts.putIfAbsent(index, data);
        }

        synchronized boolean isComplete() {
            return parts.size() >= chunks && chunks > 0;
        }

        synchronized byte[] getChunk(int index) {
            return parts.get(index);
        }
    }
}
