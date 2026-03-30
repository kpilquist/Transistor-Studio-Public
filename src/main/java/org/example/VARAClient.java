package org.example;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Minimal VARA HF TCP client for Control (default 8300) and Data (default 8301).
 * This supports a basic subset sufficient for ARQ chat: MYCALL, CONNECT/DISCONNECT,
 * LISTEN, and plain-text TX over data channel. It parses line-based control events
 * and exposes callbacks for UI integration.
 *
 * NOTE: This is a conservative first implementation. The VARA protocol has many more
 * commands/states; extend as needed per Docs/VARA Protocol Native TNC Commands.pdf
 */
public class VARAClient {

    public static class Config {
        public String host = "127.0.0.1";
        public int controlPort = 8300; // VARA HF Control
        public int dataPort = 8301;    // VARA HF Data
        public String myCall = "KD0NDG"; // default provided by user
        public int commandTimeoutMs = 5000;
    }

    private final Config config;

    private Socket controlSocket;
    private BufferedReader controlIn;
    private BufferedWriter controlOut;

    private Socket dataSocket;
    private BufferedReader dataIn;
    private BufferedWriter dataOut;

    private final ExecutorService ioExec = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Consumer<String> statusListener;     // human-readable status
    private BiConsumer<String, String> logListener; // direction, line/message
    private Consumer<String> chatListener;       // received chat text from data channel

    public VARAClient(Config cfg) {
        this.config = cfg;
    }

    public void setStatusListener(Consumer<String> statusListener) {
        this.statusListener = statusListener;
    }

    public void setLogListener(BiConsumer<String, String> logListener) {
        this.logListener = logListener;
    }

    public void setChatListener(Consumer<String> chatListener) {
        this.chatListener = chatListener;
    }

    public synchronized void connectModem() throws IOException {
        if (running.get()) return;
        controlSocket = new Socket(config.host, config.controlPort);
        controlIn = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.US_ASCII));
        controlOut = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.US_ASCII));

        dataSocket = new Socket(config.host, config.dataPort);
        dataIn = new BufferedReader(new InputStreamReader(dataSocket.getInputStream(), StandardCharsets.US_ASCII));
        dataOut = new BufferedWriter(new OutputStreamWriter(dataSocket.getOutputStream(), StandardCharsets.US_ASCII));

        running.set(true);
        if (statusListener != null) statusListener.accept("CONNECTED_TO_MODEM");
        // Apply MYCALL on connect
        if (config.myCall != null && !config.myCall.isBlank()) {
            sendControl("MYCALL " + config.myCall);
        }
        // Start readers
        ioExec.submit(this::readControlLoop);
        ioExec.submit(this::readDataLoop);
    }

    public synchronized void disconnectModem() {
        running.set(false);
        closeQuietly(controlIn);
        closeQuietly(controlOut);
        closeQuietly(controlSocket);
        closeQuietly(dataIn);
        closeQuietly(dataOut);
        closeQuietly(dataSocket);
        if (statusListener != null) statusListener.accept("DISCONNECTED_FROM_MODEM");
    }

    public void setMyCall(String call) {
        this.config.myCall = call;
        if (isConnected()) {
            sendControl("MYCALL " + call);
        }
    }

    public boolean isConnected() {
        return running.get() && controlSocket != null && controlSocket.isConnected() && dataSocket != null && dataSocket.isConnected();
    }

    public void setListen(boolean on) {
        sendControl("LISTEN " + (on ? "ON" : "OFF"));
    }

    public void connectARQ(String targetCall) {
        sendControl("CONNECT " + targetCall);
    }

    public void disconnectARQ() {
        sendControl("DISCONNECT");
    }

    public void sendChat(String line) {
        // VARA HF typically uses the Data TCP to transfer ARQ payload lines terminated by CRLF
        sendData(line + "\r\n");
    }

    /**
     * Native VARA file transfer support (send). This is a placeholder until the exact
     * command sequence is mapped from the official VARA documentation.
     *
     * API: UI calls this with progress/status/done/error callbacks. We will fill in the
     * implementation to use the Control and Data channels per VARA's spec.
     */
    public void sendFile(java.io.File file,
                         java.util.function.Consumer<Integer> onProgress,
                         java.util.function.Consumer<String> onStatus,
                         Runnable onDone,
                         java.util.function.Consumer<String> onError) {
        ioExec.submit(() -> {
            try {
                if (!isConnected()) {
                    if (onError != null) onError.accept("Not connected to VARA modem");
                    return;
                }
                if (file == null || !file.exists()) {
                    if (onError != null) onError.accept("File not found");
                    return;
                }
                if (onStatus != null) onStatus.accept("Preparing VARA native file transfer (TODO)");
                // TODO: Implement native VARA file transfer commands here.
                // For now, fail fast with a clear message so UI can show the status.
                if (onError != null) onError.accept("VARA native file transfer not implemented yet");
            } catch (Exception ex) {
                if (onError != null) onError.accept(ex.getMessage());
            }
        });
    }

    private void readControlLoop() {
        try {
            String line;
            while (running.get() && (line = controlIn.readLine()) != null) {
                log("RX-C", line);
                handleControlLine(line);
            }
        } catch (IOException e) {
            if (running.get()) notifyStatus("CONTROL_LINK_ERROR: " + e.getMessage());
        } finally {
            // If either loop ends, drop connection
            disconnectModem();
        }
    }

    private void readDataLoop() {
        try {
            String line;
            while (running.get() && (line = dataIn.readLine()) != null) {
                log("RX-D", line);
                if (chatListener != null) chatListener.accept(line);
            }
        } catch (IOException e) {
            if (running.get()) notifyStatus("DATA_LINK_ERROR: " + e.getMessage());
        } finally {
            disconnectModem();
        }
    }

    private void handleControlLine(String line) {
        // Very basic parsing for common events
        // Examples (may vary by version):
        // "CONNECTED <call>"
        // "DISCONNECTED"
        // "BUSY ON" / "BUSY OFF"
        // "SNR <val>"
        String u = line.toUpperCase();
        if (u.startsWith("CONNECTED")) {
            notifyStatus(line);
        } else if (u.startsWith("DISCONNECTED")) {
            notifyStatus(line);
        } else if (u.startsWith("BUSY ")) {
            notifyStatus(line);
        } else if (u.startsWith("SNR ")) {
            notifyStatus(line);
        } else if (u.startsWith("MYCALL ")) {
            notifyStatus(line);
        } else {
            // generic
            notifyStatus(line);
        }
    }

    private void sendControl(String cmd) {
        try {
            String out = cmd.endsWith("\r\n") ? cmd : cmd + "\r\n";
            if (controlOut != null) {
                controlOut.write(out);
                controlOut.flush();
                log("TX-C", cmd);
            }
        } catch (IOException e) {
            notifyStatus("CONTROL_SEND_ERROR: " + e.getMessage());
        }
    }

    private void sendData(String data) {
        try {
            if (dataOut != null) {
                dataOut.write(data);
                dataOut.flush();
                log("TX-D", data.trim());
            }
        } catch (IOException e) {
            notifyStatus("DATA_SEND_ERROR: " + e.getMessage());
        }
    }

    private void log(String direction, String msg) {
        if (logListener != null) logListener.accept(direction, msg);
    }

    private void notifyStatus(String status) {
        if (statusListener != null) statusListener.accept(status);
    }

    private void closeQuietly(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (IOException ignored) {}
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }
}
