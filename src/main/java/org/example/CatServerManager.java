package org.example;

import java.io.IOException;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

/**
 * Singleton that owns the lifecycle of {@link CatIpServer} and bridges it to UI settings.
 */
public class CatServerManager {
    private static final CatServerManager INSTANCE = new CatServerManager();

    public static CatServerManager getInstance() { return INSTANCE; }

    private final Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
    private static final String PREF_AUTOSTART = "catServer.autostart"; // default true
    private static final String PREF_PORT = "catServer.port"; // default 60000

    private volatile RadioControl radio;
    private volatile CatIpServer server;

    // Optional UI listeners
    private volatile Consumer<Boolean> statusListener;
    private volatile Consumer<Integer> clientCountListener;

    private CatServerManager() {}

    public synchronized void setRadioControl(RadioControl rc) {
        this.radio = rc;
        // If autostart is ON and server not running yet, start it
        boolean wantAuto = prefs.getBoolean(PREF_AUTOSTART, true);
        if (wantAuto && (server == null || !server.isRunning())) {
            ensureServerCreated();
            try {
                tryStart();
            } catch (IOException e) {
                // Log and ignore start failure at this point
                System.err.println("CatServerManager: Failed to autostart CAT server: " + e.getMessage());
            }
        }
    }

    public synchronized void setStatusListener(Consumer<Boolean> l) {
        this.statusListener = l;
        if (server != null) server.setStatusListener(l);
    }

    public synchronized void setClientCountListener(Consumer<Integer> l) {
        this.clientCountListener = l;
        if (server != null) server.setClientCountListener(l);
    }

    public int getSavedPort() {
        return prefs.getInt(PREF_PORT, 60000);
    }

    public void savePort(int port) {
        prefs.putInt(PREF_PORT, port);
        synchronized (this) {
            if (server != null) server.setPort(port);
        }
    }

    public boolean isAutostartEnabled() {
        return prefs.getBoolean(PREF_AUTOSTART, true);
    }

    public void setAutostartEnabled(boolean on) {
        prefs.putBoolean(PREF_AUTOSTART, on);
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public synchronized void start() throws IOException {
        ensureServerCreated();
        tryStart();
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop();
        }
    }

    private void ensureServerCreated() {
        if (server == null) {
            int port = prefs.getInt(PREF_PORT, 60000);
            // If radio not yet available, still create server with current radio reference; CatIpServer requires non-null
            if (radio == null) {
                // Create a placeholder RadioControl to avoid NPE? Better: delay creation until radio exists
                return;
            }
            server = new CatIpServer(radio, port);
            if (statusListener != null) server.setStatusListener(statusListener);
            if (clientCountListener != null) server.setClientCountListener(clientCountListener);
        }
    }

    private void tryStart() throws IOException {
        if (server == null) return; // radio not ready
        if (!server.isRunning()) {
            server.start();
        }
    }
}
