package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Client for communicating with Hamlib's rigctld over a TCP socket.
 * Start rigctld from the command line, e.g.: rigctld -m 1043 -r COM3 -s 38400
 */
public class HamlibClient {
    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private volatile boolean connected = false;

    // Listeners (mirroring RadioControl.java)
    private Consumer<Long> frequencyListener;
    private Consumer<String> modeListener;
    private Consumer<Boolean> txListener;
    private Consumer<Integer> sMeterListener;
    private Consumer<Boolean> connectionListener;

    // Additional controls
    private Consumer<Boolean> voxListener;
    private Consumer<Integer> afGainListener;    // 0-255
    private Consumer<Integer> rfGainListener;    // 0-255
    private Consumer<Integer> rfPowerSettingListener; // 0-100

    private ScheduledExecutorService pollingScheduler;
    private final Object ioLock = new Object();

    public void setFrequencyListener(Consumer<Long> listener) { this.frequencyListener = listener; }
    public void setModeListener(Consumer<String> listener) { this.modeListener = listener; }
    public void setTxListener(Consumer<Boolean> listener) { this.txListener = listener; }
    public void setSMeterListener(Consumer<Integer> listener) { this.sMeterListener = listener; }
    public void setConnectionListener(Consumer<Boolean> listener) { this.connectionListener = listener; }

    public void setVOXListener(Consumer<Boolean> listener) { this.voxListener = listener; }
    public void setAFGainListener(Consumer<Integer> listener) { this.afGainListener = listener; }
    public void setRFGainListener(Consumer<Integer> listener) { this.rfGainListener = listener; }
    public void setRFPowerSettingListener(Consumer<Integer> listener) { this.rfPowerSettingListener = listener; }

    public void connect(String host, int port) throws Exception {
        disconnect(); // ensure clean state
        
        socket = new Socket(host, port);
        socket.setSoTimeout(2000);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        writer = new PrintWriter(socket.getOutputStream(), true);
        connected = true;
        
        notifyListenerSafe(connectionListener, true);
        
        // Start a background polling loop to fetch state at regular intervals
        pollingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Hamlib-Poller");
            t.setDaemon(true);
            return t;
        });
        
        pollingScheduler.scheduleAtFixedRate(this::pollRadioState, 0, 200, TimeUnit.MILLISECONDS);
        System.out.println("Connected to Hamlib rigctld on " + host + ":" + port);
    }

    public void disconnect() {
        connected = false;
        if (pollingScheduler != null) {
            pollingScheduler.shutdownNow();
            pollingScheduler = null;
        }
        try { if (reader != null) reader.close(); } catch (Exception ignore) {}
        try { if (writer != null) writer.close(); } catch (Exception ignore) {}
        try { if (socket != null) socket.close(); } catch (Exception ignore) {}
        
        notifyListenerSafe(connectionListener, false);
        System.out.println("Disconnected from Hamlib.");
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Thread-safe method to send a command to rigctld and get the response.
     */
    private String sendCommand(String cmd) {
        if (!connected) return null;
        synchronized (ioLock) {
            try {
                writer.print(cmd + "\n");
                writer.flush();
                return reader.readLine();
            } catch (IOException e) {
                disconnect();
                return null;
            }
        }
    }

    // --- Control Methods ---

    public void setFrequency(long freq) {
        sendCommand("F " + freq);
        // Eagerly notify the UI
        notifyListenerSafe(frequencyListener, freq);
    }

    public void setPTT(boolean transmit) {
        sendCommand("T " + (transmit ? "1" : "0"));
        notifyListenerSafe(txListener, transmit);
    }

    public void setMode(String mode, int passband) {
        // Hamlib mode strings: USB, LSB, CW, RTTY, PKTUSB, etc.
        // Passband in Hz (0 for radio default)
        sendCommand("M " + mode + " " + passband);
        notifyListenerSafe(modeListener, mode);
    }
    
    public void setActiveVFO(char vfo) {
        String v = vfo == 'B' ? "VFO-B" : "VFO-A";
        sendCommand("V " + v);
    }

    // --- Background Polling ---

    private void pollRadioState() {
        if (!connected) return;
        
        // 1. Poll Frequency
        String freqStr = sendCommand("f");
        if (freqStr != null && !freqStr.startsWith("RPRT")) {
            try {
                long freq = Long.parseLong(freqStr.trim());
                notifyListenerSafe(frequencyListener, freq);
            } catch (NumberFormatException ignore) {}
        }
        
        // 2. Poll Mode and Passband (rigctld returns multiple lines typically)
        String modeLine = sendCommand("m");
        if (modeLine != null && !modeLine.startsWith("RPRT")) {
            String mode = modeLine.trim().split("\\s+")[0];
            if (!mode.isEmpty()) notifyListenerSafe(modeListener, mode);
            // Best-effort read second line for passband without blocking other IO
            try {
                reader.mark(128);
                String maybePb = reader.readLine();
                if (maybePb != null && !maybePb.startsWith("RPRT")) {
                    // If you decide to map passband to a UI control, do it here
                    // e.g., notifyListenerSafe(widthListener, parsePassband(maybePb));
                } else {
                    if (maybePb != null) { /* RPRT line consumed */ }
                }
            } catch (IOException ignored) {
                try { reader.reset(); } catch (IOException ignore2) {}
            }
        }
        
        // 3. Poll VOX status
        String voxStr = sendCommand("u VOX");
        if (voxStr != null && !voxStr.startsWith("RPRT")) {
            boolean vox = voxStr.trim().equals("1") || voxStr.trim().equalsIgnoreCase("on");
            notifyListenerSafe(voxListener, vox);
        }
        
        // 4. Poll AF gain (0.0-1.0)
        String afStr = sendCommand("l AF");
        if (afStr != null && !afStr.startsWith("RPRT")) {
            try {
                double v = Double.parseDouble(afStr.trim());
                int val = (int)Math.max(0, Math.min(255, Math.round(v * 255.0)));
                notifyListenerSafe(afGainListener, val);
            } catch (NumberFormatException ignore) {}
        }
        
        // 5. Poll RF gain (0.0-1.0)
        String rfStr = sendCommand("l RF");
        if (rfStr != null && !rfStr.startsWith("RPRT")) {
            try {
                double v = Double.parseDouble(rfStr.trim());
                int val = (int)Math.max(0, Math.min(255, Math.round(v * 255.0)));
                notifyListenerSafe(rfGainListener, val);
            } catch (NumberFormatException ignore) {}
        }
        
        // 6. Poll RF Power (0.0-1.0 => 0-100%)
        String pwrStr = sendCommand("l RFPOWER");
        if (pwrStr != null && !pwrStr.startsWith("RPRT")) {
            try {
                double v = Double.parseDouble(pwrStr.trim());
                int pct = (int)Math.max(0, Math.min(100, Math.round(v * 100.0)));
                notifyListenerSafe(rfPowerSettingListener, pct);
            } catch (NumberFormatException ignore) {}
        }
        
        // 7. Poll S-Meter
        // Standard Hamlib command to read signal strength: 'l STRENGTH'
        String sMeterStr = sendCommand("l STRENGTH");
        if (sMeterStr != null && !sMeterStr.startsWith("RPRT")) {
            try {
                // rigctld usually returns signal strength in dB relative to S9
                double db = Double.parseDouble(sMeterStr.trim());
                
                // Map dB to your 0-255 progress bar tracking scale
                // (e.g. -54dB = S1 = 0, 0dB = S9 = 128, +60dB = 255)
                int mapped = (int) Math.max(0, Math.min(255, (db + 54) * 2)); 
                notifyListenerSafe(sMeterListener, mapped);
            } catch (NumberFormatException ignore) {}
        }
        
        // 8. Poll PTT state
        String pttStr = sendCommand("t");
        if (pttStr != null) {
            notifyListenerSafe(txListener, pttStr.trim().equals("1"));
        }
    }

    // --- Utility ---
    private <T> void notifyListenerSafe(Consumer<T> listener, T value) {
        if (listener == null) return;
        SwingUtilities.invokeLater(() -> {
            try { listener.accept(value); } catch (Exception ignored) {}
        });
    }
}