package org.example;

import java.io.IOException;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

/**
 * Singleton that manages lifecycle of {@link RemoteWsServer} and bridges
 * radio control and audio samples to WebSocket clients.
 */
public class RemoteControlManager {
    private static final RemoteControlManager INSTANCE = new RemoteControlManager();
    public static RemoteControlManager getInstance() { return INSTANCE; }

    private final Preferences prefs = Preferences.userNodeForPackage(RemoteControlManager.class);
    private static final String PREF_AUTOSTART = "remoteWs.autostart"; // default true
    private static final String PREF_PORT = "remoteWs.port"; // default 60601

    private volatile RadioControl radio;
    private volatile RemoteWsServer server;

    private volatile Consumer<Integer> clientCountListener;

    private RemoteControlManager() {}

    public synchronized void setRadioControl(RadioControl rc) {
        this.radio = rc;
        if (isAutostartEnabled() && (server == null || !server.isRunning())) {
            ensureServer();
            tryStart();
        }
    }

    public int getSavedPort() {
        return prefs.getInt(PREF_PORT, 60601);
    }

    public void savePort(int port) {
        prefs.putInt(PREF_PORT, port);
        synchronized (this) {
            if (server != null && server.isRunning()) {
                try {
                    server.stop(1000);
                } catch (InterruptedException ignored) {}
                server = null;
                ensureServer();
                tryStart();
            }
        }
    }

    public boolean isAutostartEnabled() { return prefs.getBoolean(PREF_AUTOSTART, true); }
    public void setAutostartEnabled(boolean on) { prefs.putBoolean(PREF_AUTOSTART, on); }

    public synchronized boolean isRunning() { return server != null && server.isRunning(); }

    public synchronized void start() {
        ensureServer();
        tryStart();
    }

    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(1000);
            } catch (InterruptedException e) {
                // ignore
            }
            server = null;
        }
    }

    public void setClientCountListener(Consumer<Integer> l) {
        this.clientCountListener = l;
        synchronized (this) {
            if (server != null) server.setClientCountListener(l);
        }
    }

    public void setAudioFormat(int sampleRate, int channels) {
        RemoteWsServer s = server;
        if (s != null) s.setAudioFormat(sampleRate, channels);
    }

    public void pushAudio(float[] samples) {
        RemoteWsServer s = server;
        if (s != null) s.pushPcmFloat(samples);
    }

    private void ensureServer() {
        if (server == null) {
            if (radio == null) return; // wait until radio provided
            int port = getSavedPort();
            server = new RemoteWsServer(port, radio);
            if (clientCountListener != null) server.setClientCountListener(clientCountListener);
            server.start();
        }
    }

    private void tryStart() {
        // server.start() is invoked in ensureServer(); keep for symmetry
        if (server == null) return;
    }
}
