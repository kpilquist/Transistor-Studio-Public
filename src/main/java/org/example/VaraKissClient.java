package org.example;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * TCP KISS client to interface with a local/remote VARA modem (e.g., VARA HF) via KISS over TCP.
 *
 * Implements SLIP/KISS framing:
 * - FEND  (0xC0)
 * - FESC  (0xDB)
 * - TFEND (0xDC)
 * - TFESC (0xDD)
 * Command byte for data: CMD_DATA (0x00)
 *
 * Example usage (pseudo-code):
 * Example:
 *   VaraKissClient client = new VaraKissClient();
 *   client.setPayloadListener(bytes -> { ... });
 *   client.setConnectionListener(isConnected -> { ... });
 *   client.connect("127.0.0.1", 8100);
 *   client.sendData(myPayloadBytes);
 *   client.disconnect();
 */
public class VaraKissClient implements Closeable {
    // KISS / SLIP constants
    public static final byte FEND = (byte) 0xC0;
    public static final byte FESC = (byte) 0xDB;
    public static final byte TFEND = (byte) 0xDC;
    public static final byte TFESC = (byte) 0xDD;

    public static final byte CMD_DATA = (byte) 0x00;

    // Connection
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Listeners
    private volatile Consumer<byte[]> payloadListener;
    private volatile Consumer<Boolean> connectionListener;

    // Synchronization for writes to prevent frame interleaving
    private final Object writeLock = new Object();

    // Optional timeouts (milliseconds)
    private int connectTimeoutMs = 3000;
    private int soTimeoutMs = 0; // 0 = infinite read block

    public VaraKissClient() {}

    // Optional TX tail/delay parameter byte for KISS (0x01). Default 0x14.
    private byte txTailParam = 0x14;
    public void setTxTailParam(byte value) { this.txTailParam = value; }

    public void setPayloadListener(Consumer<byte[]> listener) {
        this.payloadListener = listener;
    }

    public void setConnectionListener(Consumer<Boolean> listener) {
        this.connectionListener = listener;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = Math.max(0, connectTimeoutMs); }

    public int getSoTimeoutMs() { return soTimeoutMs; }
    public void setSoTimeoutMs(int soTimeoutMs) {
        this.soTimeoutMs = Math.max(0, soTimeoutMs);
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            try { socket.setSoTimeout(this.soTimeoutMs); } catch (SocketException ignore) {}
        }
    }

    /**
     * Connect to VARA KISS TCP server (default 127.0.0.1:8100) and start reader thread.
     */
    public synchronized void connect(String host, int port) throws IOException {
        Objects.requireNonNull(host, "host");
        if (isConnected()) {
            // Already connected; disconnect first to reset streams/threads
            disconnect();
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        if (soTimeoutMs > 0) socket.setSoTimeout(soTimeoutMs);
        in = socket.getInputStream();
        out = socket.getOutputStream();

        running.set(true);
        readerThread = new Thread(this::readLoop, "VaraKissClient-Reader");
        readerThread.setDaemon(true);
        readerThread.start();

        notifyConnection(true);

        // Send KISS TX tail/delay parameter (0x01) as handshake for FT-710
        sendKissParam((byte)0x01, txTailParam);
    }

    /**
     * Disconnect and stop background reader.
     */
    public synchronized void disconnect() {
        running.set(false);
        // Closing the socket/input will unblock read()
        closeQuietly(in);
        closeQuietly(out);
        if (socket != null) {
            try { socket.close(); } catch (IOException ignore) {}
        }
        in = null;
        out = null;
        socket = null;

        // Wait briefly for thread to finish (avoid joining from the same thread)
        if (readerThread != null && readerThread.isAlive() && Thread.currentThread() != readerThread) {
            try { readerThread.join(200); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        }
        readerThread = null;

        notifyConnection(false);
    }

    @Override
    public void close() {
        disconnect();
    }

    /**
     * Send a payload as a KISS data frame over the TCP socket.
     */
    public void sendData(byte[] payload) {
        if (payload == null) return;
        OutputStream os = this.out;
        if (os == null) {
            // Not connected
            return;
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream(payload.length + 6);
        buf.write(FEND);
        buf.write(CMD_DATA & 0xFF);
        for (byte b : payload) {
            if (b == FEND) {
                buf.write(FESC);
                buf.write(TFEND);
            } else if (b == FESC) {
                buf.write(FESC);
                buf.write(TFESC);
            } else {
                buf.write(b & 0xFF);
            }
        }
        buf.write(FEND);

        byte[] frame = buf.toByteArray();
        try {
            synchronized (writeLock) {
                os.write(frame);
                os.flush();
            }
        } catch (IOException e) {
            // Treat as connection drop
            handleIoFailure(e);
        }
    }

    /**
     * Convenience to send ASCII text (will be encoded as UTF-8 bytes) via KISS.
     * Optional helper for apps that want to send chat strings.
     */
    public void sendText(String text) {
        if (text == null) return;
        sendData(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Send a single KISS parameter command: FEND, (0x01 + port 0), value, FEND.
     */
    private void sendKissParam(byte cmd, byte value) {
        OutputStream os = this.out;
        if (os == null) return;
        byte[] frame = new byte[] { FEND, cmd, value, FEND };
        try {
            synchronized (writeLock) {
                os.write(frame);
                os.flush();
            }
        } catch (IOException e) {
            handleIoFailure(e);
        }
    }

    private void readLoop() {
        final byte[] one = new byte[1];
        boolean inFrame = false;
        boolean esc = false;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256);
        try {
            while (running.get()) {
                int r = in.read(one, 0, 1);
                if (r == -1) throw new IOException("Stream closed");
                byte b = one[0];

                if (!inFrame) {
                    if (b == FEND) {
                        // Start of frame
                        inFrame = true;
                        esc = false;
                        frame.reset();
                    }
                    // else: drop bytes until FEND
                    continue;
                }

                // Inside a frame
                if (esc) {
                    if (b == TFEND) {
                        frame.write(FEND);
                    } else if (b == TFESC) {
                        frame.write(FESC);
                    } else {
                        // Protocol violation: write raw b
                        frame.write(b & 0xFF);
                    }
                    esc = false;
                    continue;
                }

                if (b == FESC) {
                    esc = true;
                    continue;
                }

                if (b == FEND) {
                    // End of frame; deliver if it has at least cmd byte
                    byte[] raw = frame.toByteArray();
                    if (raw.length >= 1) {
                        byte cmd = raw[0];
                        if (cmd == CMD_DATA) {
                            int len = raw.length - 1;
                            if (len > 0) {
                                byte[] payload = new byte[len];
                                System.arraycopy(raw, 1, payload, 0, len);
                                deliverPayload(payload);
                            } else {
                                // Empty payload is allowed but usually not useful
                                deliverPayload(new byte[0]);
                            }
                        } else {
                            // Other KISS commands are ignored for now
                        }
                    }
                    // Prepare for next frame
                    inFrame = false;
                    esc = false;
                    frame.reset();
                    continue;
                }

                // Regular data byte
                frame.write(b & 0xFF);
            }
        } catch (IOException e) {
            // read failed, treat as disconnect
            handleIoFailure(e);
        } finally {
            // Ensure resources are closed and state updated
            disconnect();
        }
    }

    private void deliverPayload(byte[] payload) {
        Consumer<byte[]> listener = this.payloadListener;
        if (listener != null) {
            try {
                listener.accept(payload);
            } catch (Throwable ignore) {
                // Listener exceptions should not kill our reader
            }
        }
    }

    private void notifyConnection(boolean connected) {
        Consumer<Boolean> listener = this.connectionListener;
        if (listener != null) {
            try { listener.accept(connected); } catch (Throwable ignore) {}
        }
    }

    private void handleIoFailure(IOException e) {
        // On IO failure, disconnect. Listener will be notified in disconnect().
        disconnect();
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignore) {}
    }
}
