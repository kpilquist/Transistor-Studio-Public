package org.example;

import com.fazecast.jSerialComm.SerialPort;
import javax.swing.SwingUtilities; // Swap to javafx.application.Platform if using JavaFX
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class RadioControl {
    private SerialPort serialPort;
    private SerialPort pttPort;

    public enum PttStrategy { AUTO, FORCE_SERIAL, FORCE_CAT }
    private volatile PttStrategy pttStrategy = PttStrategy.AUTO;

    public enum SerialTxControl { OFF, RTS, DTR }
    private volatile SerialTxControl serialTxControl = SerialTxControl.OFF;

    public void setSerialTxControl(SerialTxControl control) {
        if (control == null) control = SerialTxControl.OFF;
        this.serialTxControl = control;
        // Ensure lines are safe if OFF is selected
        if (pttPort != null && control == SerialTxControl.OFF) {
            try { pttPort.clearRTS(); } catch (Throwable ignore) {}
            try { pttPort.clearDTR(); } catch (Throwable ignore) {}
        }
    }
    public SerialTxControl getSerialTxControl() { return serialTxControl; }

    // --- State and Listeners ---
    private Consumer<Long> frequencyListener;
    private Consumer<Integer> powerListener;
    private Consumer<Boolean> powerStatusListener;
    private Consumer<Integer> swrListener;
    private Consumer<Integer> alcListener;
    private Consumer<Integer> sMeterListener;
    private Consumer<Integer> idListener;
    private Consumer<Integer> vddListener;
    private Consumer<Boolean> tuningStatusListener;
    private Consumer<String> modeListener;
    private java.util.function.BiConsumer<String, String> logListener;
    private Consumer<Character> vfoListener;
    private Consumer<Boolean> splitListener;
    private Consumer<Boolean> txListener;
    private Consumer<Boolean> connectionListener;
    private final CopyOnWriteArrayList<Consumer<Boolean>> connectionListeners = new CopyOnWriteArrayList<>();

    private volatile boolean splitEnabled = false;
    private volatile String currentModeCode = null;
    private volatile boolean inDataMode = false;
    private Consumer<Boolean> dataModeListener;

    private Consumer<Long> vfoAFrequencyListener;
    private Consumer<Long> vfoBFrequencyListener;
    private volatile long vfoAFrequency = 0L;
    private volatile long vfoBFrequency = 0L;

    private final CopyOnWriteArrayList<Consumer<Integer>> sMeterListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Integer>> swrListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Character>> vfoListeners = new CopyOnWriteArrayList<>();

    private volatile char currentVFO = 'A';
    private boolean isConnected = false;
    private volatile boolean isTransmitting = false;
    private volatile long lastKnownFrequency = 0;
    public long getLastKnownFrequency() { return lastKnownFrequency; }
    private volatile Integer powerStatus = null;

    private Consumer<Integer> afGainListener;
    private Consumer<Integer> rfGainListener;
    private Consumer<Integer> widthIndexListener;
    private volatile Integer lastAFGain = null;
    private volatile Integer lastRFGain = null;
    private volatile Integer lastWidthIndex = null;

    private Consumer<Integer> rfPowerSettingListener;
    private volatile Integer lastRFPowerPercent = null;
    private Consumer<Boolean> voxListener;
    private volatile Boolean lastVoxEnabled = null;

    // --- System Threads & Queues ---
    private Thread readerThread;
    private Thread commandProcessorThread;
    private Thread meterThread;

    private ScheduledExecutorService debounceScheduler;
    private volatile long pendingFreqA = -1;
    private volatile long pendingFreqB = -1;
    private volatile ScheduledFuture<?> pendingTaskA;
    private volatile ScheduledFuture<?> pendingTaskB;
    // Post-set safety nudge to recover UI if no RX arrives shortly after a control change
    private volatile ScheduledFuture<?> postSetNudge;

    private final LinkedBlockingQueue<String> commandQueue = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<String> meterQueue = new LinkedBlockingQueue<>();
    private final Object responseLock = new Object();
    private volatile String lastSentCommand = null;

    // --- Frequency formatting/parsing heuristics ---
    // Some rigs use 9 digits in Hz (Yaesu), some 10 digits in 10 Hz units, some 11 digits in Hz (Kenwood/Icom).
    // We auto-detect based on incoming responses and mirror that when sending.
    private volatile int freqDigitWidth = 9;   // default Yaesu-style 9 digits
    private volatile int freqUnitStep = 1;     // 1 = Hz units; 10 = 10 Hz units

    // --- Event-Driven & Timing Variables ---
    private volatile long lastPowerPollAt = 0L;
    private volatile boolean awaitingPowerOnConfirm = false;
    private volatile boolean postPowerInitDone = false;
    private volatile long meterPollMs = 100L; // Match requested 100ms VFO adjustment pacing
    // Suppress meter polling for a short window after interactive frequency changes
    private volatile long suppressMetersUntil = 0L;

    private volatile long lastHeartbeatTime = 0;
    private final long HEARTBEAT_TIMEOUT = 2000; // 2 seconds
    private volatile long lastHeartbeatWarnAt = 0;
    private volatile long lastHeartbeatPingAt = 0;
    private volatile long lastAiToggleAt = 0;

    private final StringBuilder rxBuffer = new StringBuilder();
    private volatile boolean lastResponseWasError = false;
    private static final String[] CAT_HEADERS = {
            "RM", "IF", "OI", "FA", "FB", "VS", "TX", "MD", "PC", "VX", "AI", "AG", "RG", "SH", "FD"
    };

    // --- Core Connection ---
    public void connect(String portName, int baudRate, int dataBits, int stopBits, int parity, int flowControl) throws Exception {
        connect(portName, baudRate, dataBits, stopBits, parity, flowControl, null);
    }

    public void connect(String portName, int baudRate, int dataBits, int stopBits, int parity, int flowControl, String pttPortName) throws Exception {
        // Ensure clean state from any previous session
        try { if (pendingTaskA != null) pendingTaskA.cancel(true); } catch (Throwable ignore) {}
        try { if (pendingTaskB != null) pendingTaskB.cancel(true); } catch (Throwable ignore) {}
        try { if (postSetNudge != null) postSetNudge.cancel(true); } catch (Throwable ignore) {}
        commandQueue.clear();
        meterQueue.clear();
        synchronized (responseLock) { rxBuffer.setLength(0); }
        resetRuntimeState();

        serialPort = SerialPort.getCommPort(portName);
        serialPort.setBaudRate(baudRate);
        serialPort.setNumDataBits(dataBits);
        serialPort.setNumStopBits(stopBits);
        serialPort.setParity(parity);
        serialPort.setFlowControl(flowControl);
        if (flowControl != SerialPort.FLOW_CONTROL_DISABLED) {
            System.err.println("Warning: Hardware flow control enabled. For FT-710, prefer FLOW_CONTROL_DISABLED unless RTS/CTS is explicitly configured in rig menu.");
        }

        if (!serialPort.openPort()) {
            throw new Exception("Failed to open serial port: " + portName);
        }

        if (pttPortName != null && !pttPortName.isEmpty() && !pttPortName.startsWith("No Serial")) {
            try {
                pttPort = SerialPort.getCommPort(pttPortName);
                pttPort.setBaudRate(9600);
                pttPort.setNumDataBits(8);
                pttPort.setNumStopBits(SerialPort.ONE_STOP_BIT);
                pttPort.setParity(SerialPort.NO_PARITY);
                pttPort.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED);
                if (!pttPort.openPort()) {
                    System.err.println("Warning: Failed to open PTT port: " + pttPortName);
                    pttPort = null;
                } else {
                    try { pttPort.clearRTS(); } catch (Throwable ignore) {}
                    try { pttPort.clearDTR(); } catch (Throwable ignore) {}
                }
            } catch (Throwable t) {
                System.err.println("Warning: Exception opening PTT port: " + t.getMessage());
                pttPort = null;
            }
        } else {
            pttPort = null;
        }

        debounceScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "RadioControl-Debounce");
            t.setDaemon(true);
            return t;
        });

        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
        isConnected = true;

        notifyListenerSafe(connectionListener, true);
        for (Consumer<Boolean> l : connectionListeners) {
            notifyListenerSafe(l, true);
        }

        startReader();
        startCommandProcessor();
        startMeterPolling();

        // Initialization sequence
        sendCommand("AI1;"); // Enable Auto Info (Event-Driven)
        syncAllState();

        lastHeartbeatTime = System.currentTimeMillis();
        System.out.println("Connected to radio on " + portName + (pttPort != null ? " (PTT on " + pttPort.getSystemPortName() + ")" : ""));
    }

    public void disconnect() {
        isConnected = false;
        // Stop threads
        if (readerThread != null) readerThread.interrupt();
        if (commandProcessorThread != null) commandProcessorThread.interrupt();
        if (meterThread != null) meterThread.interrupt();
        try { if (readerThread != null) readerThread.join(300); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        try { if (commandProcessorThread != null) commandProcessorThread.join(300); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
        try { if (meterThread != null) meterThread.join(300); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }

        // Cancel any scheduled tasks
        try { if (pendingTaskA != null) pendingTaskA.cancel(true); } catch (Throwable ignore) {}
        try { if (pendingTaskB != null) pendingTaskB.cancel(true); } catch (Throwable ignore) {}
        try { if (postSetNudge != null) postSetNudge.cancel(true); } catch (Throwable ignore) {}

        // Clear queues and buffers
        commandQueue.clear();
        meterQueue.clear();
        synchronized (responseLock) { rxBuffer.setLength(0); }

        // Shutdown scheduler
        if (debounceScheduler != null) {
            debounceScheduler.shutdownNow();
            debounceScheduler = null;
        }

        // Drop RTS/DTR for hardware PTT
        if (pttPort != null) {
            try { pttPort.clearRTS(); } catch (Throwable ignore) {}
            try { pttPort.clearDTR(); } catch (Throwable ignore) {}
        }

        // Politely return radio to safe state and close ports
        if (serialPort != null) {
            try {
                OutputStream out = serialPort.getOutputStream();
                try { out.write("TX0;".getBytes()); } catch (Exception ignore) {}
                try { out.write("AI0;".getBytes()); } catch (Exception ignore) {}
                out.flush();
            } catch (IOException ignore) {}
            serialPort.closePort();
        }
        if (pttPort != null) {
            try { pttPort.closePort(); } catch (Throwable ignore) {}
            pttPort = null;
        }

        // Reset runtime state so a reconnect starts clean
        resetRuntimeState();

        System.out.println("Disconnected from radio.");
        notifyListenerSafe(connectionListener, false);
        for (Consumer<Boolean> l : connectionListeners) {
            notifyListenerSafe(l, false);
        }

        awaitingPowerOnConfirm = false;
        lastPowerPollAt = 0L;
        powerStatus = null;
        postPowerInitDone = false;
        notifyListenerSafe(powerStatusListener, null);
    }

    // --- Helper Methods ---
    private void syncAllState() {
        sendCommand("VS;");
        sendCommand("IF;");
        sendCommand("FA;");
        sendCommand("FB;");
        sendCommand("MD;");
        sendCommand("AG0;");
        sendCommand("RG0;");
        sendCommand("SH00;");
        sendCommand("PC;");
        sendCommand("VX;");
        sendCommand("PS;");
    }

    private <T> void notifyListenerSafe(Consumer<T> listener, T value) {
        if (listener == null) return;
        SwingUtilities.invokeLater(() -> {
            try { listener.accept(value); } catch (Exception ignored) {}
        });
    }

    private int findNextHeader(int fromIndex) {
        int best = -1;
        for (String h : CAT_HEADERS) {
            int idx = rxBuffer.indexOf(h, fromIndex);
            if (idx != -1 && (best == -1 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private synchronized void sendCommand(String cmd) {
        commandQueue.offer(cmd);
    }

    private void writeToPort(String cmd) throws IOException {
        if (serialPort == null || !serialPort.isOpen()) return;
        lastSentCommand = cmd;
        log("TX", cmd);
        OutputStream out = serialPort.getOutputStream();
        out.write(cmd.getBytes());
        out.flush();
    }

    // --- Listener Setters ---
    public void setPttStrategy(PttStrategy strategy) { this.pttStrategy = (strategy == null) ? PttStrategy.AUTO : strategy; }
    public PttStrategy getPttStrategy() { return this.pttStrategy; }
    public boolean isUsingHardwarePTT() { return this.pttPort != null; }
    public String getPttPortName() { return this.pttPort != null ? this.pttPort.getSystemPortName() : null; }

    public void setFrequencyListener(Consumer<Long> listener) { this.frequencyListener = listener; }
    public void setPowerListener(Consumer<Integer> listener) { this.powerListener = listener; }
    public void setPowerStatusListener(Consumer<Boolean> listener) { this.powerStatusListener = listener; }
    public void addConnectionListener(Consumer<Boolean> listener) {
        if (listener != null) this.connectionListeners.add(listener);
    }
    public void setConnectionListener(Consumer<Boolean> listener) {
        this.connectionListener = listener;
        if (listener != null) this.connectionListeners.add(listener);
    }
    public void setAFGainListener(Consumer<Integer> listener) { this.afGainListener = listener; notifyListenerSafe(listener, lastAFGain); }
    public void setRFGainListener(Consumer<Integer> listener) { this.rfGainListener = listener; notifyListenerSafe(listener, lastRFGain); }
    public void setWidthIndexListener(Consumer<Integer> listener) { this.widthIndexListener = listener; notifyListenerSafe(listener, lastWidthIndex); }
    public void setRFPowerSettingListener(Consumer<Integer> listener) { this.rfPowerSettingListener = listener; notifyListenerSafe(listener, lastRFPowerPercent); }
    public void setVOXListener(Consumer<Boolean> listener) { this.voxListener = listener; notifyListenerSafe(listener, lastVoxEnabled); }
    public void setDataModeListener(Consumer<Boolean> listener) { this.dataModeListener = listener; notifyListenerSafe(listener, inDataMode); }
    public void setSWRListener(Consumer<Integer> listener) { this.swrListener = listener; if (listener != null) this.swrListeners.add(listener); }
    public void setSMeterListener(Consumer<Integer> listener) { this.sMeterListener = listener; if (listener != null) this.sMeterListeners.add(listener); }
    public void setIDListener(Consumer<Integer> listener) { this.idListener = listener; }
    public void setVDDListener(Consumer<Integer> listener) { this.vddListener = listener; }
    public void setALCListener(Consumer<Integer> listener) { this.alcListener = listener; }
    public void setTuningStatusListener(Consumer<Boolean> listener) { this.tuningStatusListener = listener; }
    public void setModeListener(Consumer<String> listener) { this.modeListener = listener; }
    public void setVFOListener(Consumer<Character> listener) { this.vfoListener = listener; if (listener != null) this.vfoListeners.add(listener); }

    // ATAS: Query tuning status using AC0;
    public void checkAtasStatus() {
        if (!isConnected) return;
        sendCommand("AC0;");
    }
    public void setSplitListener(Consumer<Boolean> listener) { this.splitListener = listener; }
    public void setTXListener(Consumer<Boolean> listener) { this.txListener = listener; }
    public void setVFOAFrequencyListener(Consumer<Long> listener) { this.vfoAFrequencyListener = listener; notifyListenerSafe(listener, vfoAFrequency); }
    public void setVFOBFrequencyListener(Consumer<Long> listener) { this.vfoBFrequencyListener = listener; notifyListenerSafe(listener, vfoBFrequency); }
    public void setLogListener(java.util.function.BiConsumer<String, String> listener) { this.logListener = listener; }
    // Compatibility helpers for components expecting add/remove style listeners
    public void addLogListener(java.util.function.BiConsumer<String, String> listener) { this.logListener = listener; }
    public void removeLogListener(java.util.function.BiConsumer<String, String> listener) { if (this.logListener == listener) this.logListener = null; }
    // Send raw CAT string (e.g., "IF;", "FA00014070000;")
    public void sendRaw(String cat) { sendCommand(cat); }
    // External TX intent marker (currently a no-op hook for watchdogs)
    public void markExternalTxIntent() { /* no-op hook; could update a timestamp for watchdog */ }

    public boolean isInDataMode() { return inDataMode; }
    public Integer getPowerStatus() { return powerStatus; }
    public boolean isConnected() { return isConnected; }

    private void log(String direction, String msg) {
        if (logListener != null) logListener.accept(direction, msg);
        System.out.println("RadioControl: " + direction + ": " + msg);
    }

    // --- Control Commands ---
    public void requestPowerStatus() { if (isConnected) sendCommand("PS;"); }

    public void powerOff() {
        if (!isConnected) return;
        sendCommand("PS0;");
        powerStatus = 0;
        notifyListenerSafe(powerListener, 0);
        notifyListenerSafe(powerStatusListener, false);
        awaitingPowerOnConfirm = false;
    }

    public void powerOn() {
        if (!isConnected) return;
        sendCommand("PS1;");
        awaitingPowerOnConfirm = true;
        powerStatus = null;
        postPowerInitDone = false;
        notifyListenerSafe(powerStatusListener, null);
        maybePollPowerStatus(true);
    }

    public void setHardwarePTT(boolean transmit) {
        if (pttPort == null) return;
        try {
            switch (serialTxControl) {
                case RTS:
                    if (transmit) pttPort.setRTS(); else pttPort.clearRTS();
                    break;
                case DTR:
                    if (transmit) pttPort.setDTR(); else pttPort.clearDTR();
                    break;
                case OFF:
                default:
                    // Ensure both are low
                    try { pttPort.clearRTS(); } catch (Throwable ignore) {}
                    try { pttPort.clearDTR(); } catch (Throwable ignore) {}
                    break;
            }

            if (isTransmitting != transmit) {
                isTransmitting = transmit;
                notifyListenerSafe(txListener, isTransmitting);
            }
        } catch (Throwable t) {
            System.err.println("PTT line toggle failed: " + t.getMessage());
        }
    }

    public void setPTT(boolean transmit) {
        boolean haveSerialPttPort = (pttPort != null);
        boolean haveCat = (isConnected && serialPort != null);
        boolean wantSerialLine = (serialTxControl != SerialTxControl.OFF);
        boolean useSerialLine = (pttStrategy == PttStrategy.FORCE_SERIAL || (pttStrategy == PttStrategy.AUTO && haveSerialPttPort && wantSerialLine)) && haveSerialPttPort && wantSerialLine;

        if (useSerialLine) {
            setHardwarePTT(transmit);
            return;
        }
        if (!haveCat) {
            if (!transmit && isTransmitting) {
                isTransmitting = false;
                notifyListenerSafe(txListener, false);
            }
            return;
        }

        sendCommand(transmit ? "TX1;" : "TX0;");
        sendCommand("TX;");

        if (isTransmitting != transmit) {
            isTransmitting = transmit;
            notifyListenerSafe(txListener, isTransmitting);
        }
    }

    public void setActiveVFO(char vfo) {
        char newVfo = (vfo == 'B' || vfo == 'b') ? 'B' : 'A';
        this.currentVFO = newVfo;
        notifyListenerSafe(vfoListener, newVfo);
        for (Consumer<Character> l : vfoListeners) notifyListenerSafe(l, newVfo);

        sendCommand(newVfo == 'B' ? "VS1;" : "VS0;");
        sendCommand("VS;");
        sendCommand("FA;");
        sendCommand("FB;");
        sendCommand("IF;");
    }

    public void setMeter(int meterIndex) {
        if (isConnected) sendCommand(String.format("MS%03d;", meterIndex));
    }

    public void setFrequency(long frequency) {
        if (isConnected) setFrequencyForVFO(currentVFO, frequency);
    }

    public void setFrequencyForVFO(char vfo, long frequency) {
        if (!isConnected) return;
        char vfoChar = (vfo == 'B' || vfo == 'b') ? 'B' : 'A';

        // Advisory: For General Class in 20m data sub-band, stay below 14.150 MHz
        if (inDataMode && frequency >= 14000000L && frequency <= 14350000L && frequency > 14150000L) {
            System.err.println("Advisory: Requested frequency " + (frequency/1000.0) + " kHz exceeds 20m RTTY/Data sub-band (<= 14.150 MHz).");
        }

        synchronized (this) {
            if (vfoChar == 'A') {
                pendingFreqA = frequency;
                if (pendingTaskA == null || pendingTaskA.isDone()) {
                    pendingTaskA = debounceScheduler.schedule(() -> sendBufferedFrequency('A'), meterPollMs, TimeUnit.MILLISECONDS);
                }
            } else {
                pendingFreqB = frequency;
                if (pendingTaskB == null || pendingTaskB.isDone()) {
                    pendingTaskB = debounceScheduler.schedule(() -> sendBufferedFrequency('B'), meterPollMs, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void sendBufferedFrequency(char vfo) {
        long freq;
        synchronized (this) {
            if (vfo == 'A') { freq = pendingFreqA; pendingTaskA = null; }
            else { freq = pendingFreqB; pendingTaskB = null; }
        }
        if (freq > 0) {
            long valueToSend = freq / Math.max(1, freqUnitStep);
            int width = Math.max(9, Math.min(11, freqDigitWidth));
            String fmt = String.format("F%%1$c%%2$0%dd;", width);
            // Send frequency set for the specific VFO
            sendCommand(String.format(fmt, vfo, valueToSend));

            // Temporarily suppress meter polling to prioritize control/IF traffic
            long now = System.currentTimeMillis();
            long minSuppression = Math.max(600L, 3 * meterPollMs); // at least 600 ms or 3x poll interval
            long until = now + minSuppression;
            if (until > suppressMetersUntil) suppressMetersUntil = until;
            // Drop any pre-enqueued meter requests to avoid immediate contention
            meterQueue.clear();

            // Immediately solicit a fresh IF and explicit FA/FB readback so UI updates without waiting for AI
            sendCommand("IF;");
            sendCommand(vfo == 'B' ? "FB;" : "FA;");

            // Schedule a one-shot nudge if no RX arrives shortly after the set
            try { if (postSetNudge != null) postSetNudge.cancel(true); } catch (Throwable ignore) {}
            if (debounceScheduler != null) {
                postSetNudge = debounceScheduler.schedule(() -> {
                    if (!isConnected) return;
                    long gap = System.currentTimeMillis() - lastHeartbeatTime;
                    if (gap > Math.max(700L, 6 * meterPollMs)) {
                        long n = System.currentTimeMillis();
                        long extend = Math.max(300L, 2 * meterPollMs);
                        long u = n + extend;
                        if (u > suppressMetersUntil) suppressMetersUntil = u;
                        // Gentle ping to re-stimulate responses; avoid spamming
                        sendCommand("IF;");
                        // Also query the specific VFO once
                        sendCommand(vfo == 'B' ? "FB;" : "FA;");
                        // Optionally re-assert AI1 if it's been quiet for a while
                        if (n - lastAiToggleAt > 5000L) {
                            sendCommand("AI1;");
                            lastAiToggleAt = n;
                        }
                    }
                }, Math.max(700L, 6 * meterPollMs), TimeUnit.MILLISECONDS);
            }
        }
    }

    public void setRFPower(int power) {
        if (!isConnected) return;
        power = Math.max(0, Math.min(100, power));
        sendCommand(String.format("PC%03d;", power));
    }

    public void setVOX(boolean enabled) {
        if (isConnected) sendCommand(enabled ? "VX1;" : "VX0;");
    }

    public void setTune(boolean enabled) {
        if (isConnected) sendCommand(enabled ? "AC001;" : "AC000;");
    }

    public void setMode(String modeHex) {
        if (isConnected) setModeForVFO(currentVFO, modeHex);
    }

    public void setModeForVFO(char vfo, String modeHex) {
        if (!isConnected) return;
        char targetVfo = (vfo == 'B' || vfo == 'b') ? 'B' : 'A';
        if (this.currentVFO == targetVfo) {
            sendCommand("MD" + modeHex + ";");
        } else {
            sendCommand(targetVfo == 'B' ? "VS1;" : "VS0;");
            sendCommand("MD" + modeHex + ";");
            sendCommand(this.currentVFO == 'B' ? "VS1;" : "VS0;");
            sendCommand("IF;");
        }
    }

    public void setAFGain(int value) {
        if (!isConnected) return;
        value = Math.max(0, Math.min(255, value));
        sendCommand(String.format("AG0%03d;", value));
    }

    public void setRFGain(int value) {
        if (!isConnected) return;
        value = Math.max(0, Math.min(255, value));
        sendCommand(String.format("RG0%03d;", value));
    }

    public void setNoiseBlanker(boolean enabled) { if (isConnected) sendCommand(enabled ? "NB01;" : "NB00;"); }
    public void setNoiseReduction(boolean enabled) { if (isConnected) sendCommand(enabled ? "NR01;" : "NR00;"); }
    public void setSpeechProcessor(boolean enabled) { if (isConnected) sendCommand(enabled ? "PR1;" : "PR0;"); }

    public void enableSplit() { if (isConnected) { sendCommand("FT1;"); sendCommand("IF;"); } }
    public void disableSplit() { if (isConnected) { sendCommand("FT0;"); sendCommand("IF;"); } }
    public void copyAtoB() { if (isConnected) { sendCommand("AB;"); syncAllState(); } }
    public void copyBtoA() { if (isConnected) { sendCommand("BA;"); syncAllState(); } }
    public void swapVFOs() { if (isConnected) { sendCommand("SV;"); syncAllState(); } }

    public void setWidth(int widthIndex) {
        if (!isConnected) return;
        widthIndex = Math.max(0, Math.min(23, widthIndex));
        sendCommand(String.format("SH00%02d;", widthIndex));
    }

    public void setIFShift(int value) {
        int index = (int) (value / 255.0 * 23);
        setWidth(index);
    }

    public void setATASTune(boolean start) {
        if (!isConnected) return;
        if (start) {
            new Thread(() -> {
                sendCommand("AC023;");
                notifyListenerSafe(tuningStatusListener, true);
            }).start();
        } else {
            sendCommand("AC020;");
            notifyListenerSafe(tuningStatusListener, false);
        }
    }

    // --- Data Mode Helpers ---
    private boolean isDataModeCode(String code) {
        return code != null && ("09".equalsIgnoreCase(code) || "0C".equalsIgnoreCase(code));
    }

    private void updateDataMode(String newModeCode) {
        boolean newInData = isDataModeCode(newModeCode);
        if (newInData != inDataMode) {
            inDataMode = newInData;
            notifyListenerSafe(dataModeListener, inDataMode);
        } else {
            inDataMode = newInData;
        }
    }

    private String mapIFModeToMDCode(char ifMode) {
        switch (ifMode) {
            case '0': return "01"; case '1': return "02"; case '2': return "05";
            case '3': return "03"; case '4': return "06"; case '5': return "04";
            case '7': return "07"; case '8': return "08"; case '9': return "09";
            case 'A': return "0A"; case 'B': return "0B"; case 'C': return "0C";
            case 'D': return "0D"; default: return null;
        }
    }

    // --- Threaded Readers and Processors ---
    private void startReader() {
        readerThread = new Thread(() -> {
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    String response = readResponse();
                    if (response != null && !response.isEmpty()) {
                        processResponse(response);
                    }
                } catch (IOException e) {
                    isConnected = false;
                    notifyListenerSafe(connectionListener, false);
                    for (Consumer<Boolean> l : connectionListeners) notifyListenerSafe(l, false);
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        readerThread.start();
    }

    private String readResponse() throws IOException {
        if (serialPort == null || !serialPort.isOpen()) return null;
        InputStream in = serialPort.getInputStream();

        int available = in.available();
        if (available > 0) {
            byte[] buffer = new byte[available];
            int bytesRead = in.read(buffer);
            if (bytesRead > 0) {
                String chunk = new String(buffer, 0, bytesRead);
                // Emit RX to log listener for Serial Console visibility
                try { log("RX", chunk); } catch (Throwable ignore) {}
                synchronized (responseLock) { responseLock.notifyAll(); }
                return chunk;
            }
        } else {
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return null;
    }

    private void startCommandProcessor() {
        commandProcessorThread = new Thread(() -> {
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    // Prefer user/logic commands over meter polling to avoid starvation
                    String cmd = commandQueue.poll();
                    if (cmd == null) cmd = meterQueue.poll();
                    if (cmd == null) cmd = commandQueue.poll(20, TimeUnit.MILLISECONDS);

                    if (cmd != null) {
                        writeToPort(cmd);
                    }

                    // Fixed spacing between any command to allow radio to process (FT-710 prefers ~30–50ms)
                    Thread.sleep(40);

                    // Back off if the last response indicated rejection/busy ('?')
                    if (lastResponseWasError) {
                        meterQueue.clear(); // clear meter backlog to give room
                        Thread.sleep(500);
                        lastResponseWasError = false;
                    }

                    long now = System.currentTimeMillis();
                    if (now - lastHeartbeatTime > HEARTBEAT_TIMEOUT) {
                        // Light ping to solicit a response and refresh UI/state
                        if (now - lastHeartbeatPingAt > 1000) {
                            sendCommand("IF;");
                            lastHeartbeatPingAt = now;
                        }
                        // Rate-limit warning log to avoid flooding console
                        if (now - lastHeartbeatWarnAt > 2000) {
                            log("WARN", "No heartbeat detected from radio.");
                            lastHeartbeatWarnAt = now;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    isConnected = false;
                    notifyListenerSafe(connectionListener, false);
                    for (Consumer<Boolean> l : connectionListeners) notifyListenerSafe(l, false);
                }
            }
        });
        commandProcessorThread.start();
    }

    private void startMeterPolling() {
        meterThread = new Thread(() -> {
            int txMeterIndex = 2;
            while (isConnected && !Thread.currentThread().isInterrupted()) {
                try {
                    long now = System.currentTimeMillis();
                    boolean suppressing = now < suppressMetersUntil;
                    boolean heartbeatLost = (now - lastHeartbeatTime) > HEARTBEAT_TIMEOUT;

                    if (!suppressing && !heartbeatLost) {
                        if (meterQueue.size() < 2) { // Only add if queue is draining well
                            if (isTransmitting) {
                                meterQueue.offer(String.format("RM%d;", txMeterIndex));
                                txMeterIndex = (txMeterIndex > 5) ? 2 : txMeterIndex + 1;
                            } else {
                                meterQueue.offer("RM1;"); // Poll RX S-Meter
                            }
                        }
                    }
                    // If suppressing or heartbeat lost, skip enqueuing meters to free the line for control/IF traffic

                    // Wait before next consideration. During suppression, we check more frequently.
                    Thread.sleep(suppressing ? Math.min(50L, meterPollMs) : meterPollMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        meterThread.start();
    }

    // --- Parsing Logic ---
    private void processResponse(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        rxBuffer.append(chunk);

        while (true) {
            int hdr = findNextHeader(0);
            if (hdr == -1) {
                if (rxBuffer.length() > 256) rxBuffer.delete(0, rxBuffer.length() - 64);
                break;
            }
            if (hdr > 0) rxBuffer.delete(0, hdr);
            if (rxBuffer.length() < 2) break;

            String h2 = rxBuffer.substring(0, 2);
            int endIdx = -1;
            int semi = rxBuffer.indexOf(";", 2);

            if ("IF".equals(h2)) {
                int need = 2 + 25;
                if (rxBuffer.length() >= need) {
                    if (rxBuffer.length() >= need + 1 && rxBuffer.charAt(need) == ';') {
                        endIdx = need + 1;
                    } else {
                        int nextHdr = findNextHeader(need);
                        if (nextHdr == need || semi == -1) endIdx = need;
                    }
                }
            } else if ("VS".equals(h2) || "TX".equals(h2)) {
                if (semi != -1) endIdx = semi + 1;
                else if (rxBuffer.length() >= 3 && findNextHeader(3) == 3) endIdx = 3;
            } else if ("MD".equals(h2)) {
                if (semi != -1) endIdx = semi + 1;
                else if (rxBuffer.length() >= 4 && findNextHeader(4) == 4) endIdx = 4;
            } else {
                if (semi != -1) {
                    endIdx = semi + 1;
                } else {
                    int nextHdr = findNextHeader(2);
                    if (nextHdr > 2) endIdx = nextHdr;
                }
            }

            if (endIdx == -1) break;

            String frame = rxBuffer.substring(0, endIdx);
            rxBuffer.delete(0, endIdx);

            if (!frame.endsWith(";")) frame = frame + ";";

            try { processSingleResponse(frame); } catch (Exception ignored) {}
        }
    }

    private void processSingleResponse(String response) {
        lastHeartbeatTime = System.currentTimeMillis();

        if (response.startsWith("FA") || response.startsWith("FB")) {
            int semi = response.indexOf(';');
            if (semi == -1) semi = response.length();
            if (semi > 2) {
                String digits = response.substring(2, semi).replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    try {
                        int n = digits.length();
                        long raw = Long.parseLong(digits);
                        // Auto-detect unit and width
                        if (n == 10) { freqDigitWidth = 10; freqUnitStep = 10; }
                        else if (n >= 11) { freqDigitWidth = 11; freqUnitStep = 1; }
                        else { freqDigitWidth = 9; freqUnitStep = 1; }
                        long freq = raw * Math.max(1, freqUnitStep == 10 ? 10 : 1);

                        boolean isFA = response.startsWith("FA");
                        if (isFA) {
                            vfoAFrequency = freq;
                            notifyListenerSafe(vfoAFrequencyListener, freq);
                        } else {
                            vfoBFrequency = freq;
                            notifyListenerSafe(vfoBFrequencyListener, freq);
                        }
                        if ((isFA && currentVFO == 'A') || (!isFA && currentVFO == 'B')) {
                            lastKnownFrequency = freq;
                            notifyListenerSafe(frequencyListener, freq);
                        }
                    } catch (NumberFormatException ignore) {}
                }
            }
        } else if (response.startsWith("IF")) {
            String body = response.substring(2);
            // Extract leading digits as frequency field (varies by rig)
            int i = 0;
            while (i < body.length() && Character.isDigit(body.charAt(i))) i++;
            if (i > 0) {
                try {
                    String digits = body.substring(0, i);
                    int n = digits.length();
                    long raw = Long.parseLong(digits);
                    if (n == 10) { freqDigitWidth = 10; freqUnitStep = 10; }
                    else if (n >= 11) { freqDigitWidth = 11; freqUnitStep = 1; }
                    else { freqDigitWidth = 9; freqUnitStep = 1; }
                    long freq = raw * Math.max(1, freqUnitStep == 10 ? 10 : 1);
                    lastKnownFrequency = freq;
                    notifyListenerSafe(frequencyListener, freq);
                } catch (Exception e) {}
            }
            // Parse mode/split if present using legacy offsets when available
            if (body.length() >= 24) {
                try {
                    char modeChar = body.charAt(20);
                    String modeCode = mapIFModeToMDCode(modeChar);
                    if (modeCode != null) {
                        currentModeCode = modeCode;
                        updateDataMode(modeCode);
                        notifyListenerSafe(modeListener, modeCode);
                    }
                    boolean newSplit = (body.charAt(23) == '1');
                    if (newSplit != splitEnabled) {
                        splitEnabled = newSplit;
                        notifyListenerSafe(splitListener, splitEnabled);
                    }
                } catch (Exception ignore) {}
            }
        } else if (response.startsWith("TX")) {
            if (response.length() >= 3) {
                boolean newTx = (response.charAt(2) == '1' || response.charAt(2) == '2');
                if (newTx != isTransmitting) {
                    isTransmitting = newTx;
                    notifyListenerSafe(txListener, isTransmitting);
                } else {
                    notifyListenerSafe(txListener, isTransmitting);
                }
            }
        } else if (response.startsWith("MD")) {
            if (response.length() >= 4) {
                String mode = response.substring(2, 4);
                currentModeCode = mode;
                updateDataMode(mode);
                notifyListenerSafe(modeListener, mode);
            }
        } else if (response.startsWith("VS")) {
            if (response.length() >= 3) {
                char v = (response.charAt(2) == '1') ? 'B' : 'A';
                if (v != currentVFO) {
                    currentVFO = v;
                    notifyListenerSafe(vfoListener, v);
                    for (Consumer<Character> l : vfoListeners) notifyListenerSafe(l, v);
                }
            }
        } else if (response.startsWith("RM")) {
            parseRM(response);
        } else if (response.startsWith("AC")) {
            // ATAS status parsing
            // AC023; -> Start tuning command acknowledgement (we already set listener true when sending)
            // AC020; -> Stop tuning command acknowledgement
            // AC0S;  -> Status query response where S is 0:idle,1:tuning,2:done,3:error
            if (response.startsWith("AC0")) {
                int semi = response.indexOf(';');
                char s = (response.length() >= 4) ? response.charAt(3) : 'X';
                if (semi != -1 && (s == '0' || s == '1' || s == '2' || s == '3')) {
                    boolean tuning = (s == '1');
                    // Treat '2' (completed) and '3' (error) as not tuning
                    notifyListenerSafe(tuningStatusListener, tuning);
                }
            }
        } else if (response.startsWith("?")) {
            // Command rejected / radio busy
            lastResponseWasError = true;
        } else if (response.startsWith("PS")) {
            if (response.length() >= 3) {
                Integer newStatus = (response.charAt(2) == '1') ? 1 : 0;
                powerStatus = newStatus;
                if (newStatus == 1) {
                    awaitingPowerOnConfirm = false;
                    postPowerReinitIfNeeded();
                } else {
                    postPowerInitDone = false;
                }
                notifyListenerSafe(powerListener, newStatus);
                notifyListenerSafe(powerStatusListener, newStatus == 1);
            }
        } else if (response.startsWith("AG0") && response.length() >= 7) {
            try {
                int val = Math.max(0, Math.min(255, Integer.parseInt(response.substring(3, 6))));
                if (lastAFGain == null || lastAFGain != val) {
                    lastAFGain = val;
                    notifyListenerSafe(afGainListener, val);
                }
            } catch (NumberFormatException ignore) {}
        } else if (response.startsWith("RG0") && response.length() >= 7) {
            try {
                int val = Math.max(0, Math.min(255, Integer.parseInt(response.substring(3, 6))));
                if (lastRFGain == null || lastRFGain != val) {
                    lastRFGain = val;
                    notifyListenerSafe(rfGainListener, val);
                }
            } catch (NumberFormatException ignore) {}
        } else if (response.startsWith("SH00") && response.length() >= 7) {
            try {
                int idx = Math.max(0, Math.min(23, Integer.parseInt(response.substring(4, 6))));
                if (lastWidthIndex == null || lastWidthIndex != idx) {
                    lastWidthIndex = idx;
                    notifyListenerSafe(widthIndexListener, idx);
                }
            } catch (NumberFormatException ignore) {}
        } else if (response.startsWith("PC") && response.length() >= 6) {
            try {
                int pct = Math.max(0, Math.min(100, Integer.parseInt(response.substring(2, 5))));
                if (lastRFPowerPercent == null || !lastRFPowerPercent.equals(pct)) {
                    lastRFPowerPercent = pct;
                    notifyListenerSafe(rfPowerSettingListener, pct);
                }
            } catch (NumberFormatException ignore) {}
        } else if (response.startsWith("VX") && response.length() >= 3 && response.endsWith(";")) {
            boolean enabled = (response.charAt(2) == '1');
            if (lastVoxEnabled == null || lastVoxEnabled != enabled) {
                lastVoxEnabled = enabled;
                notifyListenerSafe(voxListener, enabled);
            }
        } else if (response.startsWith("?")) {
            log("RX", "? (command rejected). Last TX: " + (lastSentCommand != null ? lastSentCommand.trim() : ""));
        }
    }

    private void parseRM(String resp) {
        try {
            if (awaitingPowerOnConfirm) postPowerReinitIfNeeded();
            String content = resp.substring(2, resp.length() - 1);
            if (content.length() < 4) return;

            int type = Integer.parseInt(content.substring(0, 1));
            int value = Integer.parseInt(content.substring(1, 4));

            if (type == 1) {
                if (powerStatus == null || powerStatus != 1) {
                    powerStatus = 1;
                    awaitingPowerOnConfirm = false;
                    postPowerReinitIfNeeded();
                    notifyListenerSafe(powerStatusListener, true);
                }
                notifyListenerSafe(sMeterListener, value);
                for (Consumer<Integer> l : sMeterListeners) notifyListenerSafe(l, value);
            }
            if (type == 2) notifyListenerSafe(powerListener, value);
            if (type == 3) notifyListenerSafe(alcListener, value);
            if (type == 4 || type == 0) {
                notifyListenerSafe(swrListener, value);
                for (Consumer<Integer> l : swrListeners) notifyListenerSafe(l, value);
            }
            if (type == 5) notifyListenerSafe(idListener, value);
            if (type == 6) notifyListenerSafe(vddListener, value);

            if (powerStatus == null || powerStatus != 1 || awaitingPowerOnConfirm) {
                maybePollPowerStatus(false);
            }
        } catch (Exception ignore) {}
    }

    private void maybePollPowerStatus(boolean force) {
        if (!isConnected) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastPowerPollAt) < 1000L) return;
        lastPowerPollAt = now;
        sendCommand("PS;");
    }

    private void postPowerReinitIfNeeded() {
        if (!isConnected || postPowerInitDone) return;
        postPowerInitDone = true;
        sendCommand("AI1;");
        syncAllState();
    }

    // Reset transient runtime state so a new connection/session starts clean
    private void resetRuntimeState() {
        try { if (pendingTaskA != null) pendingTaskA.cancel(true); } catch (Throwable ignore) {}
        try { if (pendingTaskB != null) pendingTaskB.cancel(true); } catch (Throwable ignore) {}
        try { if (postSetNudge != null) postSetNudge.cancel(true); } catch (Throwable ignore) {}
        pendingTaskA = null;
        pendingTaskB = null;
        postSetNudge = null;
        pendingFreqA = -1L;
        pendingFreqB = -1L;

        // Clear queues and parsing buffer
        commandQueue.clear();
        meterQueue.clear();
        synchronized (responseLock) { rxBuffer.setLength(0); }

        // Reset timing and heartbeat
        long now = System.currentTimeMillis();
        suppressMetersUntil = 0L;
        lastHeartbeatTime = now;
        lastHeartbeatWarnAt = 0L;
        lastHeartbeatPingAt = 0L;
        lastAiToggleAt = 0L;

        // Reset cached states that should not leak across sessions
        isTransmitting = false;
        splitEnabled = false;
        currentModeCode = null;
        inDataMode = false;
        vfoAFrequency = 0L;
        vfoBFrequency = 0L;
        lastKnownFrequency = 0L;
        currentVFO = 'A';

        // Reset formatting heuristics to conservative defaults
        freqDigitWidth = 9;
        freqUnitStep = 1;
    }
}