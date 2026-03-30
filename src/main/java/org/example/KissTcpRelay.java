package org.example;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple KISS TCP relay.
 * Listens for a single VXP client on a local TCP port (default 8100) and bridges bytes to/from
 * a VARA modem's KISS TCP endpoint (host:port). All KISS framing is passed through unchanged.
 *
 * On connect to VARA, sends a KISS parameter command to set TX Tail/Delay (0x01) suitable for FT-710.
 */
public class KissTcpRelay implements Closeable {
    private final int listenPort;
    private final String varaHost;
    private final int varaPort;
    private final byte txTailParam; // e.g., 0x14 (~20 units)

    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public KissTcpRelay(int listenPort, String varaHost, int varaPort, byte txTailParam) {
        this.listenPort = listenPort;
        this.varaHost = varaHost == null ? "127.0.0.1" : varaHost;
        this.varaPort = varaPort;
        this.txTailParam = txTailParam;
    }

    public void start() throws IOException {
        if (running.get()) return;
        server = new ServerSocket(listenPort);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "KissTcpRelay-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
        System.out.println("KISS Relay listening on tcp://0.0.0.0:" + listenPort + " -> VARA " + varaHost + ":" + varaPort);
    }

    private void acceptLoop() {
        while (running.get()) {
            try (Socket client = server.accept()) {
                client.setTcpNoDelay(true);
                System.out.println("KISS client connected: " + client.getRemoteSocketAddress());
                try (Socket vara = new Socket()) {
                    vara.connect(new InetSocketAddress(varaHost, varaPort), 3000);
                    vara.setTcpNoDelay(true);
                    // Send KISS TX tail/delay: FEND, 0x01, <value>, FEND
                    try {
                        OutputStream os = vara.getOutputStream();
                        os.write(new byte[] {(byte)0xC0, 0x01, txTailParam, (byte)0xC0});
                        os.flush();
                    } catch (Throwable ignored) {}

                    // Start bidirectional pumping
                    Thread tUp = new Thread(() -> pump(client, vara), "KissRelay-Up");
                    Thread tDown = new Thread(() -> pump(vara, client), "KissRelay-Down");
                    tUp.setDaemon(true); tDown.setDaemon(true);
                    tUp.start(); tDown.start();

                    // Wait until either side closes
                    try { tUp.join(); } catch (InterruptedException ignored) {}
                    try { tDown.join(); } catch (InterruptedException ignored) {}
                } catch (IOException ioe) {
                    System.err.println("KISS Relay: VARA connect failed: " + ioe.getMessage());
                }
            } catch (IOException e) {
                if (running.get()) System.err.println("KISS Relay accept error: " + e.getMessage());
            }
        }
    }

    private void pump(Socket src, Socket dst) {
        final byte[] buf = new byte[4096];
        try (InputStream in = src.getInputStream(); OutputStream out = dst.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try { src.close(); } catch (IOException ignored) {}
            try { dst.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
        if (acceptThread != null) acceptThread.interrupt();
    }
}
