package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Simple CAT-over-TCP server that exposes the connected radio to network clients.
 * Protocol: Raw CAT strings, delimited by ';' (semicolon). Clients send commands (e.g., "IF;"),
 * and the server forwards radio RX lines back to all clients as they arrive.
 *
 * Default port: 60000
 */
public class CatIpServer {
    private final RadioControl radio;
    private volatile int port;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<ClientConn> clients = new CopyOnWriteArrayList<>();
    private ExecutorService clientPool;

    // Listeners
    private Consumer<Boolean> statusListener; // true=started, false=stopped
    private Consumer<Integer> clientCountListener; // current connected clients
    private BiConsumer<String, String> radioLogBridge; // internal: bridge radio RX to clients

    public CatIpServer(RadioControl radio, int port) {
        this.radio = radio;
        this.port = port;
    }

    public synchronized void setPort(int port) {
        this.port = port;
        // If already running, requires restart by caller
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setStatusListener(Consumer<Boolean> listener) {
        this.statusListener = listener;
    }

    public void setClientCountListener(Consumer<Integer> listener) {
        this.clientCountListener = listener;
    }

    public synchronized void start() throws IOException {
        if (running.get()) return;
        serverSocket = new ServerSocket(port);
        clientPool = Executors.newCachedThreadPool();
        running.set(true);

        // Bridge radio RX logs to all clients
        radioLogBridge = (dir, msg) -> {
            if (!"RX".equals(dir)) return; // forward only radio responses/events
            broadcast(msg);
        };
        try {
            radio.addLogListener(radioLogBridge);
        } catch (Throwable ignore) {}

        acceptThread = new Thread(this::acceptLoop, "CatIpServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        if (statusListener != null) try { statusListener.accept(true); } catch (Exception ignore) {}
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignore) {}
        if (acceptThread != null) acceptThread.interrupt();
        // Close clients
        for (ClientConn c : clients) { c.close(); }
        clients.clear();
        if (clientPool != null) {
            clientPool.shutdownNow();
            clientPool = null;
        }
        if (radioLogBridge != null) {
            try { radio.removeLogListener(radioLogBridge); } catch (Throwable ignore) {}
            radioLogBridge = null;
        }
        if (statusListener != null) try { statusListener.accept(false); } catch (Exception ignore) {}
        notifyClientCount();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket s = serverSocket.accept();
                s.setTcpNoDelay(true);
                ClientConn conn = new ClientConn(s);
                clients.add(conn);
                notifyClientCount();
                if (clientPool != null) clientPool.submit(conn::run);
            } catch (SocketException se) {
                // Usually thrown when serverSocket is closed during stop()
                break;
            } catch (IOException e) {
                if (!running.get()) break;
                // Minor delay to avoid tight loop on repeated failures
                try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
            }
        }
    }

    private void broadcast(String msg) {
        byte[] bytes = (msg == null ? "" : msg).getBytes(StandardCharsets.US_ASCII);
        for (ClientConn c : clients) {
            c.send(bytes);
        }
    }

    private void notifyClientCount() {
        if (clientCountListener != null) {
            try { clientCountListener.accept(clients.size()); } catch (Exception ignore) {}
        }
    }

    private class ClientConn {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;
        private final AtomicBoolean open = new AtomicBoolean(true);

        ClientConn(Socket socket) throws IOException {
            this.socket = socket;
            this.out = socket.getOutputStream();
            this.in = socket.getInputStream();
        }

        void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
                 PrintWriter writer = new PrintWriter(out, true, StandardCharsets.US_ASCII)) {

                // Optionally greet client
                writer.print(";CATIP READY;\n");
                writer.flush();

                StringBuilder cmd = new StringBuilder();
                while (open.get()) {
                    int ch = reader.read();
                    if (ch == -1) break;
                    char c = (char) ch;
                    cmd.append(c);
                    if (c == ';') {
                        String cat = cmd.toString().trim();
                        cmd.setLength(0);
                        if (!cat.isEmpty()) {
                            // Detect TX-on intents to mark external origin for watchdog
                            try {
                                String upper = cat.toUpperCase();
                                if (upper.startsWith("TX1;") || upper.startsWith("TX2;") || upper.startsWith("AC001;")) {
                                    try { radio.markExternalTxIntent(); } catch (Throwable ignore) {}
                                }
                                // Forward to radio as raw CAT. Do not echo to client; rely on radio RX to provide responses.
                                radio.sendRaw(cat);
                            } catch (Throwable t) {
                                // If radio is not connected, optionally send an error line
                                try {
                                    writer.print(";ERR NO RADIO;\n");
                                    writer.flush();
                                } catch (Exception ignore2) {}
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // Connection dropped
            } finally {
                close();
            }
        }

        void send(byte[] data) {
            if (!open.get()) return;
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            try { socket.close(); } catch (IOException ignore) {}
            clients.remove(this);
            notifyClientCount();
        }
    }
}
