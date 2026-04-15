package org.example;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Remote WebSocket server for commands and audio streaming.
 *
 * Protocol overview:
 * - Text frames: JSON objects with a required field "type".
 *   Supported types sent by client:
 *     {"type":"ping"} → reply {"type":"pong"}
 *     {"type":"getStatus"}
 *     {"type":"cmd","name":"setFrequency","hz":7050000}
 *     {"type":"cmd","name":"ptt","tx":true}
 *     {"type":"cmd","name":"setMode","code":"01"}  // hex MD code if applicable
 *     {"type":"cmd","name":"raw","cat":"IF;"}      // raw CAT string
 *
 * - Binary frames: server → client audio frames with a minimal header.
 *   Layout (little-endian):
 *     [4 bytes magic 'P''C''M''A']
 *     [int32 sampleRate]
 *     [int16 channels]
 *     [int32 samplesPerChannel]
 *     [int16 * channels * samplesPerChannel payload]
 */
public class RemoteWsServer extends WebSocketServer {
    private final Gson gson = new Gson();
    private final RadioControl radio;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // Clients that opted-in to receive audio
    private final Set<WebSocket> audioSubscribers = new CopyOnWriteArraySet<>();

    // Optional listener for client count updates
    private Consumer<Integer> clientCountListener;

    // Track active connections (Java-WebSocket doesn't expose connections() publicly)
    private final Set<WebSocket> conns = new CopyOnWriteArraySet<>();

    // Audio config
    private volatile int audioSampleRate = 48000; // default; will be updated by producer
    private volatile int audioChannels = 1;
    private final AtomicBoolean audioStreamingEnabled = new AtomicBoolean(true);

    public RemoteWsServer(int port, RadioControl radio) {
        super(new InetSocketAddress(port));
        this.radio = radio;
    }

    public void setClientCountListener(Consumer<Integer> l) { this.clientCountListener = l; }

    public boolean isRunning() { return running.get(); }

    @Override
    public void onStart() {
        setConnectionLostTimeout(20);
        running.set(true);
        notifyClientCount();
        System.out.println("RemoteWsServer started on ws://0.0.0.0:" + getPort());
    }

    @Override
    public void stop(int timeout) throws InterruptedException {
        running.set(false);
        super.stop(timeout);
        audioSubscribers.clear();
        notifyClientCount();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        conns.add(conn);
        // Auto-subscribe to audio if enabled
        if (audioStreamingEnabled.get()) {
            audioSubscribers.add(conn);
        }
        sendOk(conn, "welcome", new JsonObject());
        notifyClientCount();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        audioSubscribers.remove(conn);
        conns.remove(conn);
        notifyClientCount();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject m = gson.fromJson(message, JsonObject.class);
            if (m == null || !m.has("type")) {
                sendErr(conn, "badRequest", "Missing type");
                return;
            }
            String type = m.get("type").getAsString();
            switch (type) {
                case "ping" -> sendJson(conn, obj("type","pong"));
                case "getStatus" -> sendJson(conn, buildStatus());
                case "cmd" -> handleCommand(conn, m);
                case "audio" -> handleAudioControl(conn, m);
                default -> sendErr(conn, "badRequest", "Unknown type: "+type);
            }
        } catch (Exception ex) {
            sendErr(conn, "exception", ex.getMessage());
        }
    }

    private void handleAudioControl(WebSocket conn, JsonObject m) {
        String action = str(m, "action", "");
        switch (action) {
            case "subscribe" -> {
                audioSubscribers.add(conn);
                sendOk(conn, "audio", obj("subscribed", true));
            }
            case "unsubscribe" -> {
                audioSubscribers.remove(conn);
                sendOk(conn, "audio", obj("subscribed", false));
            }
            case "enable" -> {
                audioStreamingEnabled.set(true);
                sendOk(conn, "audio", obj("enabled", true));
            }
            case "disable" -> {
                audioStreamingEnabled.set(false);
                sendOk(conn, "audio", obj("enabled", false));
            }
            default -> sendErr(conn, "badRequest", "Unknown audio action");
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
        // No binary messages expected from client at the moment
        sendErr(conn, "badRequest", "Binary input not supported");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (conn != null) {
            audioSubscribers.remove(conn);
        }
        System.err.println("WS error: " + ex.getMessage());
    }

    private void handleCommand(WebSocket conn, JsonObject m) {
        String name = str(m, "name", "");
        try {
            switch (name) {
                case "ptt" -> {
                    boolean tx = bool(m, "tx", false);
                    radio.setPTT(tx);
                    sendOk(conn, "ptt", obj("tx", tx));
                }
                case "setFrequency" -> {
                    long hz = m.get("hz").getAsLong();
                    radio.setFrequency(hz);
                    sendOk(conn, "setFrequency", obj("hz", hz));
                }
                case "setMode" -> {
                    String code = str(m, "code", null);
                    if (code == null) { sendErr(conn, "badRequest", "Missing code"); return; }
                    radio.setMode(code);
                    sendOk(conn, "setMode", obj("code", code));
                }
                case "raw" -> {
                    String cat = str(m, "cat", null);
                    if (cat == null) { sendErr(conn, "badRequest", "Missing cat"); return; }
                    radio.sendRaw(cat);
                    sendOk(conn, "raw", obj("cat", cat));
                }
                default -> sendErr(conn, "badRequest", "Unknown command: "+name);
            }
        } catch (Exception ex) {
            sendErr(conn, "exception", ex.getMessage());
        }
    }

    private JsonObject buildStatus() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "status");
        try { s.addProperty("connected", radio.isConnected()); } catch (Throwable ignore) {}
        try { s.addProperty("freqHz", radio.getLastKnownFrequency()); } catch (Throwable ignore) {}
        try { s.addProperty("dataMode", radio.isInDataMode()); } catch (Throwable ignore) {}
        return s;
    }

    private void sendOk(WebSocket conn, String what, JsonObject payload) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "ok");
        o.addProperty("what", what);
        if (payload != null) {
            for (Map.Entry<String, JsonElement> e : payload.entrySet()) {
                o.add(e.getKey(), e.getValue());
            }
        }
        sendJson(conn, o);
    }

    private void sendErr(WebSocket conn, String code, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("code", code);
        o.addProperty("message", msg);
        sendJson(conn, o);
    }

    private void sendJson(WebSocket conn, JsonObject o) {
        try { conn.send(gson.toJson(o)); } catch (Exception ignore) {}
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }
    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : def;
    }
    private static JsonObject obj(Object k, Object v) {
        JsonObject o = new JsonObject();
        o.addProperty(String.valueOf(k), String.valueOf(v));
        return o;
    }

    private void notifyClientCount() {
        if (clientCountListener != null) {
            try { clientCountListener.accept(conns.size()); } catch (Exception ignore) {}
        }
    }

    // === Audio streaming ===

    public void setAudioFormat(int sampleRate, int channels) {
        this.audioSampleRate = sampleRate;
        this.audioChannels = Math.max(1, channels);
    }

    public void pushPcmFloat(float[] samples) {
        if (!audioStreamingEnabled.get() || samples == null || samples.length == 0) return;
        if (audioSubscribers.isEmpty()) return;
        // Convert to int16 little-endian mono or stereo (assume incoming mono)
        int channels = Math.max(1, audioChannels);
        int frames = samples.length / channels; // if mono, frames = samples.length
        short[] s16 = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float x = samples[i];
            if (x > 1f) x = 1f; else if (x < -1f) x = -1f;
            s16[i] = (short) Math.round(x * 32767.0);
        }
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 2 + 4 + s16.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'P').put((byte) 'C').put((byte) 'M').put((byte) 'A');
        buf.putInt(audioSampleRate);
        buf.putShort((short) channels);
        buf.putInt(frames);
        for (short v : s16) buf.putShort(v);
        buf.flip();
        broadcastBinary(buf);
    }

    private void broadcastBinary(ByteBuffer data) {
        for (WebSocket c : conns) {
            if (audioSubscribers.contains(c)) {
                try { c.send(data.asReadOnlyBuffer()); } catch (Exception ignore) {}
            }
        }
    }
}
