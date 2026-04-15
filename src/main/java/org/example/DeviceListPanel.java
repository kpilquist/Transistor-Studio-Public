package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import javax.sound.sampled.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.prefs.Preferences;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.fazecast.jSerialComm.SerialPort;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Instant;
import java.util.Objects;

// Band plan enforcement
import org.example.BandPlan.License;
import org.example.BandPlan.ModeCategory;

public class DeviceListPanel extends JPanel {
    private static final String HAMLIB_OPTION_LABEL = "Hamlib (rigctld)";
    private HamlibClient hamlibClient;
    // VXP (KISS) client and UI elements
    private VaraKissClient vxpClient;
    private JTextField vxpHostField;
    private JTextField vxpPortField;
    private JTextField vxpMyCallField;
    private JTextField vxpDestField;
    private JButton vxpStartBtn;
    private JButton vxpStopBtn;
    private JButton vxpSendMsgBtn;
    private JButton vxpSendFileBtn;
    private JLabel vxpStatusLbl;
    private JProgressBar vxpFileProgress;
    private JLabel vxpFileStatus;
    private JTextArea vxpMessageArea;
    private NodeTracker vxpTracker;
    private MeshFlowPanel vxpMesh;
        private JTextArea vxpRxArea;
    // Per-selection stream scanners to extract callsigns from decoded text
    private final java.util.Map<String, CleanStreamScanner> selectionScanners = new java.util.HashMap<>();
    // Fallback scanner for single-decoder modes (e.g., JRadio)
    private CleanStreamScanner defaultScanner = null;
    // Sliders promoted to fields for programmatic updates
    private JSlider afGainSlider;
    private JSlider rfGainSlider;
    private JSlider widthSlider;
    // RF Power slider and label
    private JSlider rfPowerSlider;
    private JLabel rfPowerValueLabel;
    // Bottom-of-panel audio controls
    private JSlider appVolumeSlider;
    private JCheckBox muteCheckBox;
    private JLabel sampleRateLabel;
    private int lastNonMutedVolumePct = 100;
    private boolean updatingAppVolume = false;
    // VOX toggle
    private JToggleButton voxButton;
    // Guard to prevent echo when programmatically updating slider values from CAT
    private boolean updatingRadioSliders = false;
    private boolean updatingRFPower = false;
    private boolean updatingVox = false;
    // PSK Reporter
    private PSKReporterPanel pskReporterPanel;
    private IPFIXExporter pskExporter;
    private String pskExporterHost;
    private int pskExporterPort;
    private final Pattern callsignPattern = Pattern.compile("(?i)\\b([A-Z]{1,2}\\d[A-Z0-9]{1,3}(?:/[A-Z0-9]+)?)\\b");
    private int lastSMeterValue = 0;
    // VARA HF Integration Fields
    private JPanel varaPanel;
    private JTextField varaHostField;
    private JTextField varaCtrlPortField;
    private JTextField varaDataPortField;
    private JTextField varaMyCallField;
    private JTextField varaTargetField;
    private JButton varaConnectBtn;
    private JButton varaDisconnectBtn;
    private JButton varaCallBtn;
    private JButton varaSendFileBtn;
    private JProgressBar varaFileProgress;
    private JLabel varaFileStatus;
    private JCheckBox varaListenCb;
    private JCheckBox varaAutoReconnectCb;
    private JLabel varaStatusLbl;
    private VARAClient varaClient;
    private volatile boolean varaConnected = false;
    private volatile boolean varaLinkUp = false;
    private JComboBox<String> serialPortComboBox;
    // New: Dedicated PTT port (Standard COM Port) for hardware RTS control
    private JComboBox<String> pttPortComboBox;
    private JComboBox<Integer> baudRateComboBox;
    private JComboBox<Integer> dataBitsComboBox;
    private JComboBox<String> stopBitsComboBox;
    private JComboBox<String> parityComboBox;
    private JComboBox<String> flowControlComboBox;
    private JComboBox<String> serialTxControlComboBox;

    private JComboBox<String> audioInputComboBox;
    private JComboBox<String> audioOutputComboBox;
    private JComboBox<String> bandwidthComboBox;
    private JComboBox<String> colorThemeComboBox;
    private JComboBox<String> speedComboBox;
    private JComboBox<String> digitalModeComboBox;
    private JSlider resolutionSlider;
    private JCheckBox fftMultithreadCheckBox;
    private JCheckBox autoConnectCheckBox;
    private JButton connectButton;
    private JCheckBox catTcpEnableCheckBox;
    private JButton refreshButton;
    private JTabbedPane tabbedPane;
    private JPanel connectionPanel;
    private JPanel waterfallSettingsPanel;
    private JPanel digitalSettingsPanel;
    private JPanel radioControlPanel; // Added
    private JPanel decodersPanel; // Decoder tab panel
    private JPanel decodersListPanel; // Holds decoder control boxes
    private ButtonGroup decoderActiveGroup = new ButtonGroup();
    private java.util.List<DecoderControlBox> decoderBoxes = new java.util.ArrayList<>();
    private java.util.Map<String, DecoderControlBox> decoderBoxesById = new java.util.concurrent.ConcurrentHashMap<>();
    private ContactLogPanel contactLogPanel;
    private AudioCapture audioCapture;
    // Recent audio ring buffer for autodetection trials (approx 2 seconds at current sample rate)
    private float[] recentAudioBuf = null;
    private int recentAudioPos = 0;
    private int recentAudioSampleRate = 0;
    private final Object recentAudioLock = new Object();
        private AudioPlayback audioPlayback;
        private Consumer<float[]> currentAudioListener;
    
        // Sticky audio device handling
        private AudioDeviceWatcher audioWatcher;
        private volatile boolean audioRxActive = false;
        private volatile boolean audioTxActive = false;
        private AudioDeviceWatcher.Identity desiredInputId;
        private AudioDeviceWatcher.Identity desiredOutputId;

    // Digital: RTTY tuning controls
    private JPanel rttyTuningPanel;
    private JSpinner rttyShiftSpinner;
    private JSpinner rttyBaudSpinner;
    private JCheckBox rttyInvertCheckBox;

    // Digital: PSK tuning controls
    private JPanel pskTuningPanel;
    private JSpinner pskBandwidthSpinner; // Hz
    private JSpinner pskSquelchSpinner; // 0-100 % lock squelch

    // Digital: CW tuning controls
    private JPanel cwTuningPanel;
    private JCheckBox cwAutoWpmCheckBox;
    private JSpinner cwWpmSpinner;
    private JSpinner cwSquelchSpinner;

    private SpectrogramDisplay waterfall;
    private RadioControl radioControl;
    private ChatBoxPanel chatBoxPanel;
    // Chat TX idle/toggle state for Start button
    private volatile boolean chatTxActive = false;
    private SignalDecoder decoder;
    private SignalEncoder encoder;
    // Background feeder for default decoder (e.g., JRadio modes) to avoid blocking audio thread
    private DecoderFeeder defaultDecoderFeeder;

    // TX UI state
    private String activeMarkerId;

    private JLabel frequencyDisplay; // Added
    private long currentFrequency = 0; // Added
    private TrackingProgressBar powerMeter;
    private TrackingProgressBar alcMeter;
    private TrackingProgressBar swrMeter;
    private TrackingProgressBar sMeter; // Added
    private TrackingProgressBar idMeter; // Added
    private TrackingProgressBar vddMeter; // Added
    private JToggleButton tuneButton; // Added
    private JComboBox<String> modeComboBox; // Added
    private JToggleButton vfoAButton; // Added VFO A button
    private JToggleButton vfoBButton; // Added VFO B button
    private JToggleButton splitToggle; // Split mode toggle
    private JLabel activeVfoLabel; // Shows which VFO is active
    private char activeVfo = 'A'; // Track active VFO
    private boolean isUpdatingUI = false; // Flag to prevent listener loops

    // 1 MHz band slider controls
    private JSlider band1MHzSlider;
    private JLabel band1MHzLabel;
    private long bandBaseHz = 0L; // floor(currentFrequency/1e6)*1e6

    private SerialConsolePanel serialConsole; // Changed from consoleArea/consolePanel
    private JSplitPane mainSplitPane;
    private JCheckBox showSerialConsoleCheckBox;
    
    private Preferences prefs;


    private JSpinner radarStartFreqSpinner;
    private JSpinner radarEndFreqSpinner;
    private JToggleButton radarLockToggle;
    private JLabel radarStatusLabel;
    private boolean radarLocked = false;
    private double radarCarrierFreq = 0;

    public RadioControl getRadioControl() {
        if (radioControl == null) {
            radioControl = new RadioControl();
        }
        return radioControl;
    }

    // Detach the Radio Control tab and return the panel so it can be placed elsewhere (e.g., left section)
    public JPanel detachRadioControlPanelFromTabs() {
        if (radioControlPanel == null || tabbedPane == null) return radioControlPanel;
        int idx = tabbedPane.indexOfComponent(radioControlPanel);
        if (idx != -1) {
            tabbedPane.removeTabAt(idx);
        }
        return radioControlPanel;
    }

    // Constants for keys
    private static final String PREF_SERIAL_PORT = "serialPort";
    private static final String PREF_SERIAL_PORT_SERIAL_NUMBER = "serialPortSerialNumber";
    private static final String PREF_SERIAL_PORT_DESCRIPTION = "serialPortDescription";
    private static final String PREF_BAUD_RATE = "baudRate";
    private static final String PREF_DATA_BITS = "dataBits";
    private static final String PREF_STOP_BITS = "stopBits";
    private static final String PREF_PARITY = "parity";
    private static final String PREF_FLOW_CONTROL = "flowControl";
    private static final String PREF_AUDIO_INPUT = "audioInput";
    private static final String PREF_AUDIO_OUTPUT = "audioOutput";
    // Hardware PTT (Standard COM Port) preferences
    private static final String PREF_PTT_PORT = "pttPort";
    private static final String PREF_PTT_PORT_SERIAL_NUMBER = "pttPortSerialNumber";
    private static final String PREF_PTT_PORT_DESCRIPTION = "pttPortDescription";
    // Strict mixer identity strings: name|vendor|description|version
    private static final String PREF_AUDIO_INPUT_ID = "audioInputId";
    private static final String PREF_AUDIO_OUTPUT_ID = "audioOutputId";
    private static final String PREF_DIGITAL_MODE = "digitalMode";
    private static final String PREF_AUTO_CONNECT = "autoConnect";
    private static final String PREF_SHOW_SERIAL_CONSOLE = "showSerialConsole";
    // UI layout + slider persistence keys
    private static final String PREF_MAIN_SPLIT_DIVIDER = "mainSplitDivider"; // pixel location from top
    private static final String PREF_APP_VOLUME_PCT = "appVolumePct"; // 0..100
    private static final String PREF_APP_MUTED = "appMuted"; // boolean
    private static final String PREF_BAND1MHZ_OFFSET = "band1MHzOffset"; // 0..1,000,000 relative to band base
    // RTTY tuning prefs
    private static final String PREF_RTTY_SHIFT = "rttyShift";
    private static final String PREF_RTTY_BAUD = "rttyBaud";
    private static final String PREF_RTTY_INVERT = "rttyInvert";
    private static final String PREF_BOUNDARY_LICENSE = "boundaryLicense"; // OFF, TECHNICIAN, GENERAL, EXTRA
    // CW prefs
    private static final String PREF_CW_TONE = "cwToneHz";
    private static final String PREF_CW_WPM = "cwWpm";
    private static final String PREF_CALLSIGN = "myCallsign";
    private static final String PREF_CAT_PORT = "catIpPort"; // default 60000
    private static final String PREF_SERIAL_TX_CONTROL = "serialTxControl"; // OFF, RTS, DTR

    private static final String[] MODE_LABELS = {
        "LSB", "USB", "CW-U", "FM", "AM",
        "RTTY-L", "CW-L", "RTTY-U",
        "DATA-L", "DATA-U", "DATA-FM",
        "FM-N", "DATA-FM-N", "PSK"
    };
    private static final String[] MODE_CODES = {
        "01", "02", "03", "04", "05",
        "06", "07", "08",
        "09", "0A", "0B",
        "0C", "0D", "0E"
    };

    // FPS Tracking
    private long lastFpsTime = 0;
    private int frameCount = 0;
    private javax.swing.Timer meterRefreshTimer;

    private final java.util.Map<String, SignalDecoder> activeDecoders = new java.util.concurrent.ConcurrentHashMap<>();
    // Serializes backfill→live processing to each decoder to avoid interleaving
    private final java.util.Map<String, DecoderFeeder> decoderFeeders = new java.util.concurrent.ConcurrentHashMap<>();

    // ---- Boundary / Band plan helpers ----
    private License getSelectedLicense() {
        if (prefs == null) return License.OFF;
        String val = prefs.get(PREF_BOUNDARY_LICENSE, "OFF");
        try {
            return License.valueOf(val);
        } catch (Exception ex) {
            return License.OFF;
        }
    }

    private static final String PREF_BOUNDARY_ENABLE_DIGITAL = "boundaryEnableDigital";
    private static final String PREF_BOUNDARY_ENABLE_VOICE = "boundaryEnableVoice";

    private boolean isDigitalTxAllowedNow() {
        if (prefs == null) return true;
        boolean enabled = prefs.getBoolean(PREF_BOUNDARY_ENABLE_DIGITAL, false);
        if (!enabled) return true; // enforcement disabled for digital
        License lic = getSelectedLicense();
        if (lic == License.OFF) return true; // license enforcement disabled
        long freq = this.currentFrequency;
        if (freq <= 0) return true; // No CAT frequency known; allow
        String modeName = (digitalModeComboBox != null && digitalModeComboBox.getSelectedItem() != null)
                ? digitalModeComboBox.getSelectedItem().toString()
                : "";
        boolean ok = BandPlan.isTxAllowed(lic, freq, ModeCategory.DIGITAL, modeName);
        if (!ok) {
            String msg = "Digital TX blocked for license: " + lic + "\n" +
                         "Current frequency: " + formatFrequency(freq) + "\n\n" +
                         "Allowed DIGITAL ranges:" + "\n" + BandPlan.allowedRangesSummary(lic, ModeCategory.DIGITAL);
            JOptionPane.showMessageDialog(this, msg, "Out-of-band (Digital)", JOptionPane.WARNING_MESSAGE);
        }
        return ok;
    }

    private boolean isVoiceTxAllowedNow() {
        if (prefs == null) return true;
        boolean enabled = prefs.getBoolean(PREF_BOUNDARY_ENABLE_VOICE, false);
        if (!enabled) return true; // enforcement disabled for voice
        License lic = getSelectedLicense();
        if (lic == License.OFF) return true;
        long freq = this.currentFrequency;
        if (freq <= 0) return true;
        boolean ok = BandPlan.isTxAllowed(lic, freq, ModeCategory.VOICE, null);
        if (!ok) {
            String msg = "Voice TX blocked for license: " + lic + "\n" +
                         "Current frequency: " + formatFrequency(freq) + "\n\n" +
                         "Allowed VOICE ranges:" + "\n" + BandPlan.allowedRangesSummary(lic, ModeCategory.VOICE);
            JOptionPane.showMessageDialog(this, msg, "Out-of-band (Voice)", JOptionPane.WARNING_MESSAGE);
        }
        return ok;
    }

    // Helpers for Chat/Markers
    private void refreshChatMarkers() {
        if (chatBoxPanel == null) return;
        List<SpectrogramSelection> sels = waterfall != null ? waterfall.getSelections() : null;
        chatBoxPanel.setMarkers(sels);
        // Re-assert the active TX binding so the pinned view mirrors the selected marker after updates
        if (activeMarkerId != null && !activeMarkerId.isEmpty()) {
            chatBoxPanel.setActiveTxMarker(activeMarkerId);
        }
    }

    private double getActiveCenterHzOrDefault() {
        // Returns audio baseband tone frequency (Hz) for the active selection, not RF absolute.
        double fallback = 1500.0;
        if (waterfall == null) return fallback;
        List<SpectrogramSelection> sels = waterfall.getSelections();
        if (sels == null || sels.isEmpty()) return fallback;
        SpectrogramSelection active = null;
        if (activeMarkerId != null) {
            for (SpectrogramSelection s : sels) {
                if (activeMarkerId.equals(s.getId())) { active = s; break; }
            }
        }
        if (active == null) active = sels.get(0);
        
        double centerAbs = active.getStartFrequency() + active.getBandwidth() / 2.0;
        double baseStart = 0.0;
        if (waterfall instanceof Waterfall wf) {
            baseStart = wf.getStartFrequency();
        }
        double audioHz = centerAbs - baseStart;
        // Clamp to [0, Nyquist]
        int sr = (audioCapture != null) ? audioCapture.getSampleRate() : 48000;
        if (audioHz < 0) audioHz = 0;
        if (audioHz > sr / 2.0) audioHz = sr / 2.0;
        return audioHz;
    }

    public DeviceListPanel(List<String> serialPorts, ChatBoxPanel chatBoxPanel) {
        prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
        this.chatBoxPanel = chatBoxPanel;

        chatBoxPanel.addInputListener(text -> {
            // Boundary check for DIGITAL TX (based on license)
            if (!isDigitalTxAllowedNow()) {
                // Block transmission
                chatBoxPanel.appendText("TX blocked (out-of-band for selected license)\n");
                return;
            }
            // If VARA HF link is active, send over VARA; else fall back to local encoder/audio
            if (varaClient != null && varaConnected && varaLinkUp) {
                varaClient.sendChat(text);
                chatBoxPanel.appendText("TX(VARAHF): " + text + "\n");
                return;
            }
            if (encoder != null && audioPlayback != null) {
                // Run in background to avoid blocking UI
                new Thread(() -> {
                    boolean wasIdle = chatTxActive; // remember if idle/continuous TX was active
                    try {
                        // Engage PTT (hardware RTS if available, else CAT) for digital TX
                        try { rcSetPTT(true); } catch (Throwable ignore) {}
                        // If an idle stream is currently active, stop it before sending message
                        if (wasIdle) {
                            try {
                                audioPlayback.stopStream();
                                audioPlayback.stopLoop();
                                audioPlayback.stopTone();
                            } catch (Throwable ignore) {}
                        }
                        SignalEncoder enc = encoder;
                        int sr = audioPlayback.getSampleRate();
                        // Align encoder to active marker when applicable
                        String mode = (String) digitalModeComboBox.getSelectedItem();
                        double centerHz = getActiveCenterHzOrDefault();
                        if (enc instanceof PSK31Encoder) {
                            ((PSK31Encoder) enc).setCarrierFrequency((int) Math.round(centerHz));
                        } else if (enc instanceof RTTYEncoder rtty) {
                            // Read UI settings
                            int shift = 170;
                            double baud = 45.45;
                            boolean invert = false;
                            if (rttyShiftSpinner != null && rttyShiftSpinner.getValue() instanceof Number) {
                                shift = ((Number) rttyShiftSpinner.getValue()).intValue();
                            }
                            if (rttyBaudSpinner != null && rttyBaudSpinner.getValue() instanceof Number) {
                                baud = ((Number) rttyBaudSpinner.getValue()).doubleValue();
                            }
                            if (rttyInvertCheckBox != null) {
                                invert = rttyInvertCheckBox.isSelected();
                            }
                            // Compute Mark/Space around center
                            double half = shift / 2.0;
                            int mark = (int) Math.round(centerHz - half);
                            int space = (int) Math.round(centerHz + half);
                            if (invert) {
                                int tmp = mark; mark = space; space = tmp;
                            }
                            // Clamp to audio sample rate Nyquist
                            int maxTone = Math.max(1, Math.min(sr / 2 - 1, Math.max(mark, space)));
                            int minTone = Math.max(1, Math.min(sr / 2 - 1, Math.min(mark, space)));
                            // If clamped collapsed, fallback to defaults
                            if (maxTone - minTone < Math.max(10, shift / 2)) {
                                minTone = Math.max(1, 2125 - shift / 2);
                                maxTone = Math.max(1, 2125 + shift / 2);
                            }
                            // Apply
                            rtty.setFrequencies(minTone, maxTone);
                            rtty.setBaudRate(baud);
                            // Inform user
                            chatBoxPanel.appendText(String.format("RTTY TX @ center=%.0f Hz, mark=%d Hz, space=%d Hz, baud=%.2f\n", centerHz, minTone, maxTone, baud));
                        } else if (enc instanceof CWEncoder cw) {
                            cw.setToneHz((int) Math.round(centerHz));
                            if (cwWpmSpinner != null && cwWpmSpinner.getValue() instanceof Number) {
                                cw.setWpm(((Number) cwWpmSpinner.getValue()).intValue());
                            }
                        }
                        float[] samples = enc.generateSamples(text, sr);
                        audioPlayback.play(samples);
                        // After main transmit, append a short close tail per mode so receivers see a proper close and audio returns fully to idle
                        String modeNow = (String) digitalModeComboBox.getSelectedItem();
                        double centerHzNow = getActiveCenterHzOrDefault();
                        if ("PSK31".equals(modeNow) || "PSK".equals(modeNow)) {
                            audioPlayback.playCloseTailPsk31((int) Math.round(centerHzNow), 80);
                        } else if (modeNow != null && (modeNow.startsWith("RTTY") || "RTTY".equals(modeNow))) {
                            // Use MARK as the close tone
                            int shift = rttyShiftSpinner != null && rttyShiftSpinner.getValue() instanceof Number ? ((Number) rttyShiftSpinner.getValue()).intValue() : 170;
                            boolean invert = rttyInvertCheckBox != null && rttyInvertCheckBox.isSelected();
                            double half = shift / 2.0;
                            int mark = (int) Math.round(centerHzNow - half);
                            int space = (int) Math.round(centerHzNow + half);
                            if (invert) { int tmp = mark; mark = space; space = tmp; }
                            // Clamp
                            mark = Math.max(1, Math.min(mark, sr / 2 - 1));
                            audioPlayback.playCloseTailRtty(mark, 100);
                        }

                        // Resume idle if it was active before send
                        if (wasIdle) {
                            try {
                                double cHz = getActiveCenterHzOrDefault();
                                int sampleRate = audioPlayback.getSampleRate();
                                if ("PSK31".equals(modeNow) || "PSK".equals(modeNow)) {
                                    audioPlayback.startPsk31Idle((int) Math.round(cHz));
                                } else if (modeNow != null && (modeNow.startsWith("RTTY") || "RTTY".equals(modeNow))) {
                                    int shift2 = rttyShiftSpinner != null && rttyShiftSpinner.getValue() instanceof Number ? ((Number) rttyShiftSpinner.getValue()).intValue() : 170;
                                    double baud2 = rttyBaudSpinner != null && rttyBaudSpinner.getValue() instanceof Number ? ((Number) rttyBaudSpinner.getValue()).doubleValue() : 45.45;
                                    boolean invert2 = rttyInvertCheckBox != null && rttyInvertCheckBox.isSelected();
                                    double half2 = shift2 / 2.0;
                                    int mark2 = (int) Math.round(cHz - half2);
                                    int space2 = (int) Math.round(cHz + half2);
                                    if (invert2) { int tmp2 = mark2; mark2 = space2; space2 = tmp2; }
                                    mark2 = Math.max(1, Math.min(mark2, sampleRate / 2 - 1));
                                    space2 = Math.max(1, Math.min(space2, sampleRate / 2 - 1));
                                    audioPlayback.startRttyIdle(mark2, space2, baud2);
                                }
                                // Keep chatTxActive true and Start button in active state
                                chatTxActive = true;
                                chatBoxPanel.setTxActive(true);
                            } catch (Throwable resumeErr) {
                                // If resume fails, drop state gracefully
                                chatTxActive = false;
                                chatBoxPanel.setTxActive(false);
                                try { rcSetPTT(false); } catch (Throwable ignore) {}
                            }
                        }
                    } catch (Exception ex) {
                        chatBoxPanel.appendText("TX error: " + ex.getMessage() + "\n");
                    } finally {
                        // Release PTT if we were not in idle before sending
                        if (!wasIdle) {
                            try { rcSetPTT(false); } catch (Throwable ignore) {}
                        }
                    }
                }).start();
                // Echo to chat
                chatBoxPanel.appendText("TX(Audio): " + text + "\n");
            }
        });

        // Wire additional Chat controls
        chatBoxPanel.setMarkerChangeListener(id -> activeMarkerId = id);
        chatBoxPanel.setStartClickListener(() -> {
            String mode = (String) digitalModeComboBox.getSelectedItem();
            if (audioPlayback == null) return;
            if (!chatTxActive) {
                double centerHz = getActiveCenterHzOrDefault();
                int sr = audioPlayback.getSampleRate();
                try {
                    // Engage PTT (hardware RTS if available, else CAT) for idle start (non-blocking)
                    try { rcSetPTT(true); } catch (Throwable ignore) {}
                    if ("PSK31".equals(mode) || "PSK".equals(mode)) {
                        // Start a continuous PSK31 idle stream (no looping buffer)
                        audioPlayback.startPsk31Idle((int) Math.round(centerHz));
                        chatBoxPanel.appendText(String.format("PSK idle started @ %.0f Hz\n", centerHz));
                    } else if (mode != null && (mode.startsWith("RTTY") || "RTTY".equals(mode))) {
                        int shift = rttyShiftSpinner != null && rttyShiftSpinner.getValue() instanceof Number ? ((Number) rttyShiftSpinner.getValue()).intValue() : 170;
                        double baud = rttyBaudSpinner != null && rttyBaudSpinner.getValue() instanceof Number ? ((Number) rttyBaudSpinner.getValue()).doubleValue() : 45.45;
                        boolean invert = rttyInvertCheckBox != null && rttyInvertCheckBox.isSelected();
                        double half = shift / 2.0;
                        int mark = (int) Math.round(centerHz - half);
                        int space = (int) Math.round(centerHz + half);
                        if (invert) { int tmp = mark; mark = space; space = tmp; }
                        // Clamp
                        mark = Math.max(1, Math.min(mark, sr / 2 - 1));
                        space = Math.max(1, Math.min(space, sr / 2 - 1));
                        // Start a continuous RTTY idle stream (no looped buffer)
                        audioPlayback.startRttyIdle(mark, space, baud);
                        chatBoxPanel.appendText(String.format("RTTY idle started @ center=%.0f Hz (mark=%d, space=%d, baud=%.2f)\n", centerHz, mark, space, baud));
                    }
                    chatTxActive = true;
                    chatBoxPanel.setTxActive(true);
                } catch (Exception ex) {
                    // On failure, ensure state is consistent
                    chatTxActive = false;
                    chatBoxPanel.setTxActive(false);
                    try { rcSetPTT(false); } catch (Throwable ignore) {}
                }
            } else {
                // Currently active: stop TX idle and drop PTT
                try {
                    audioPlayback.stopAllTx();
                } finally {
                    try { rcSetPTT(false); } catch (Throwable ignore) {}
                    chatTxActive = false;
                    chatBoxPanel.setTxActive(false);
                }
            }
        });
        chatBoxPanel.setPauseClickListener(() -> {
            if (audioPlayback == null) return;
            try {
                String mode = (String) digitalModeComboBox.getSelectedItem();
                double centerHz = getActiveCenterHzOrDefault();
                int sr = audioPlayback.getSampleRate();
                if ("PSK31".equals(mode)) {
                    audioPlayback.playCloseTailPsk31((int) Math.round(centerHz), 80);
                } else if (mode != null && (mode.startsWith("RTTY") || "RTTY".equals(mode))) {
                    int shift = rttyShiftSpinner != null && rttyShiftSpinner.getValue() instanceof Number ? ((Number) rttyShiftSpinner.getValue()).intValue() : 170;
                    boolean invert = rttyInvertCheckBox != null && rttyInvertCheckBox.isSelected();
                    double half = shift / 2.0;
                    int mark = (int) Math.round(centerHz - half);
                    int space = (int) Math.round(centerHz + half);
                    if (invert) { int tmp = mark; mark = space; space = tmp; }
                    mark = Math.max(1, Math.min(mark, sr / 2 - 1));
                    audioPlayback.playCloseTailRtty(mark, 100);
                } else {
                    // Fallback for other modes
                    audioPlayback.stopLoop();
                    audioPlayback.stopTone();
                    audioPlayback.stopStream();
                }
            } catch (Exception ex) {
                // If anything goes wrong, ensure we stop
                audioPlayback.stopAllTx();
            } finally {
                // Drop PTT on pause (hardware RTS if available, else CAT)
                try { rcSetPTT(false); } catch (Throwable ignore) {}
                // Update UI/state
                chatTxActive = false;
                chatBoxPanel.setTxActive(false);
            }
        });
        chatBoxPanel.setStopClickListener(() -> {
            if (audioPlayback != null) { audioPlayback.stopAllTx(); }
            try { rcSetPTT(false); } catch (Throwable ignore) {}
            // Update UI/state
            chatTxActive = false;
            chatBoxPanel.setTxActive(false);
        });
        chatBoxPanel.setStartPressListener(() -> {
            String mode = (String) digitalModeComboBox.getSelectedItem();
            if (!"CW".equals(mode)) return;
            if (audioPlayback == null) return;
            int hz = 700;
            if (encoder instanceof CWEncoder) {
                hz = ((CWEncoder) encoder).getToneHz();
            }
            audioPlayback.startTone(hz);
        });
        chatBoxPanel.setStartReleaseListener(() -> {
            String mode = (String) digitalModeComboBox.getSelectedItem();
            if (!"CW".equals(mode)) return;
            if (audioPlayback != null) audioPlayback.stopTone();
        });

        setLayout(new BorderLayout());

        // Console setup (respect preference)
        boolean showSerialConsole = prefs.getBoolean(PREF_SHOW_SERIAL_CONSOLE, false);
        serialConsole = showSerialConsole ? new SerialConsolePanel() : null;

        // Create tabbed pane
        tabbedPane = new JTabbedPane();
        // Allow the right side (tabs/decoders) to shrink and scroll rather than push back on Radio Control priority
        tabbedPane.setMinimumSize(new Dimension(120, 100));

        // Create connection panel
        connectionPanel = createConnectionPanel();

        // Create waterfall settings panel
        waterfallSettingsPanel = createWaterfallSettingsPanel();

        // Create digital settings panel
        digitalSettingsPanel = createDigitalSettingsPanel();

        // Create radio control panel
        radioControlPanel = createRadioControlPanel();
        // Start with radio controls disabled until a CAT connection is established
        setRadioControlsEnabled(false);

        // Create VARA HF panel
        varaPanel = createVARAHFPanel();

        // Create Decoders tab panel
        decodersPanel = createDecodersPanel();

        // Add panels to tabbed pane
        tabbedPane.addTab("Connection", connectionPanel);
        tabbedPane.addTab("Radio Control", radioControlPanel); // Added
        tabbedPane.addTab("Waterfall Settings", waterfallSettingsPanel);
        tabbedPane.addTab("Digital", digitalSettingsPanel);
        tabbedPane.addTab("Decoders", decodersPanel);
        // Optionally include PSK Reporter tab (hidden by default for first release)
        boolean showPskReporter = false;
        if (showPskReporter) {
            pskReporterPanel = new PSKReporterPanel();
            tabbedPane.addTab("PSK Reporter", pskReporterPanel);
        } else {
            pskReporterPanel = null;
        }
        // Create and add Contact Log tab (to the right)
        contactLogPanel = new ContactLogPanel();
        tabbedPane.addTab("Log", contactLogPanel);
        // Initialize contact log context
        if (contactLogPanel != null) {
            if (currentFrequency > 0) contactLogPanel.setContextFrequency(currentFrequency);
            // Rig mode (from CAT/UI)
            String initialRigMode = null;
            if (modeComboBox != null && modeComboBox.getSelectedIndex() >= 0 && modeComboBox.getSelectedIndex() < MODE_LABELS.length) {
                initialRigMode = MODE_LABELS[modeComboBox.getSelectedIndex()];
            }
            if (initialRigMode != null && !initialRigMode.isEmpty()) {
                contactLogPanel.setContextMode(initialRigMode);
            }
            // Digital mode (from Digital tab)
            if (digitalModeComboBox != null && digitalModeComboBox.getSelectedItem() != null) {
                String initialDigitalMode = digitalModeComboBox.getSelectedItem().toString();
                if (initialDigitalMode != null && !initialDigitalMode.isEmpty()) {
                    contactLogPanel.setContextDigitalMode(initialDigitalMode);
                }
            }
        }

        // Split Pane
        JComponent bottomComponent;
        if (serialConsole != null) {
            bottomComponent = serialConsole;
        } else {
            JPanel placeholder = new JPanel();
            placeholder.setPreferredSize(new Dimension(0, 0));
            bottomComponent = placeholder;
        }
        mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabbedPane, bottomComponent);
        mainSplitPane.setResizeWeight(1.0); // Give all space to top
        if (serialConsole == null) {
            mainSplitPane.setDividerSize(0);
        } else {
            mainSplitPane.setOneTouchExpandable(true);
        }
        mainSplitPane.setDividerLocation(1.0); // Initially collapsed (mostly)
        // Restore saved divider location if available
        try {
            if (prefs != null) {
                int saved = prefs.getInt(PREF_MAIN_SPLIT_DIVIDER, -1);
                if (saved >= 0) {
                    mainSplitPane.setDividerLocation(saved);
                }
            }
        } catch (Throwable ignore) {}
        // Save divider location when it moves or layout changes
        mainSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            try { if (prefs != null) prefs.putInt(PREF_MAIN_SPLIT_DIVIDER, mainSplitPane.getDividerLocation()); } catch (Throwable ignore) {}
        });
        mainSplitPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                try { if (prefs != null) prefs.putInt(PREF_MAIN_SPLIT_DIVIDER, mainSplitPane.getDividerLocation()); } catch (Throwable ignore) {}
            }
        });

        // Add the split pane to the center
        add(mainSplitPane, BorderLayout.CENTER);

        // Initial population of lists
        refreshDeviceLists();

        // Load saved settings
        loadSettings();
    }

    private void toggleSerialConsole(boolean show) {
        if (mainSplitPane == null) return;
        if (show) {
            if (serialConsole == null) {
                serialConsole = new SerialConsolePanel();
            }
            mainSplitPane.setBottomComponent(serialConsole);
            mainSplitPane.setDividerSize(5);
            mainSplitPane.setOneTouchExpandable(true);
            int height = getHeight();
            if (height <= 0) height = 600;
            // Try to restore last saved divider; else default to a quarter-height console
            int saved = -1;
            try { if (prefs != null) saved = prefs.getInt(PREF_MAIN_SPLIT_DIVIDER, -1); } catch (Throwable ignore) {}
            if (saved >= 0) {
                mainSplitPane.setDividerLocation(saved);
            } else {
                int consoleHeight = Math.max(120, Math.min(250, height / 4));
                mainSplitPane.setDividerLocation(height - consoleHeight);
            }
        } else {
            JPanel placeholder = new JPanel();
            placeholder.setPreferredSize(new Dimension(0, 0));
            mainSplitPane.setBottomComponent(placeholder);
            mainSplitPane.setDividerSize(0);
            mainSplitPane.setDividerLocation(1.0);
        }
        revalidate();
        repaint();
    }

    private JPanel createConnectionPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Radio Connection"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Serial Port Selection
        gbc.gridx = 0;
        gbc.gridy = 0;
        panel.add(new JLabel("Serial Port (CAT):"), gbc);

        serialPortComboBox = new JComboBox<>();
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(serialPortComboBox, gbc);

        // PTT Port (Standard COM Port) Selection
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("PTT Port (Standard):"), gbc);

        pttPortComboBox = new JComboBox<>();
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(pttPortComboBox, gbc);

        // Baud Rate
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Baud Rate:"), gbc);
        baudRateComboBox = new JComboBox<>(new Integer[]{4800, 9600, 19200, 38400, 57600, 115200});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(baudRateComboBox, gbc);

        // Data Bits
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Data Bits:"), gbc);
        dataBitsComboBox = new JComboBox<>(new Integer[]{5, 6, 7, 8});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(dataBitsComboBox, gbc);

        // Stop Bits
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Stop Bits:"), gbc);
        stopBitsComboBox = new JComboBox<>(new String[]{"1", "1.5", "2"});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(stopBitsComboBox, gbc);

        // Parity
        gbc.gridx = 0;
        gbc.gridy = 5;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Parity:"), gbc);
        parityComboBox = new JComboBox<>(new String[]{"None", "Odd", "Even", "Mark", "Space"});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(parityComboBox, gbc);

        // Flow Control
        gbc.gridx = 0;
        gbc.gridy = 6;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Flow Control:"), gbc);
        flowControlComboBox = new JComboBox<>(new String[]{"None", "RTS/CTS", "XON/XOFF"});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(flowControlComboBox, gbc);

        // Audio Input Selection
        gbc.gridx = 0;
        gbc.gridy = 7;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Audio Input (RX):"), gbc);

        audioInputComboBox = new JComboBox<>();
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(audioInputComboBox, gbc);

        // Audio Output Selection
        gbc.gridx = 0;
        gbc.gridy = 8;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Audio Output (TX):"), gbc);

        audioOutputComboBox = new JComboBox<>();
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(audioOutputComboBox, gbc);

        // Refresh Button
        refreshButton = new JButton("Refresh Devices");
        refreshButton.addActionListener(e -> refreshDeviceLists());
        gbc.gridx = 0;
        gbc.gridy = 9;
        gbc.gridwidth = 1;
        gbc.weightx = 0.5;
        panel.add(refreshButton, gbc);

        // Connect Button
        connectButton = new JButton("Connect");
        connectButton.addActionListener(e -> {
            if (connectButton.getText().equals("Connect")) {
                connect();
            } else {
                disconnect();
            }
        });
        gbc.gridx = 1;
        gbc.gridy = 9;
        gbc.gridwidth = 1;
        gbc.weightx = 0.5;
        panel.add(connectButton, gbc);

        // CAT over TCP enable (near the power/connect button)
        catTcpEnableCheckBox = new JCheckBox("Enable CAT TCP control");
        try {
            CatServerManager mgr = CatServerManager.getInstance();
            boolean enabled = mgr.isAutostartEnabled(); // defaults to true per manager
            catTcpEnableCheckBox.setSelected(enabled);
            int port = mgr.getSavedPort();
            catTcpEnableCheckBox.setToolTipText("Allow external apps to control the radio via CAT-over-TCP on port " + port + ".");
        } catch (Throwable ignore) {}
        catTcpEnableCheckBox.addActionListener(e -> {
            try {
                CatServerManager mgr = CatServerManager.getInstance();
                boolean on = catTcpEnableCheckBox.isSelected();
                mgr.setAutostartEnabled(on);
                if (on) {
                    try { mgr.start(); } catch (Exception ex) {
                        JOptionPane.showMessageDialog(this, "Failed to start CAT TCP server: " + ex.getMessage(), "CAT TCP", JOptionPane.ERROR_MESSAGE);
                        catTcpEnableCheckBox.setSelected(false);
                        mgr.setAutostartEnabled(false);
                    }
                } else {
                    mgr.stop();
                }
            } catch (Throwable t) {
                JOptionPane.showMessageDialog(this, "CAT TCP toggle failed: " + t.getMessage(), "CAT TCP", JOptionPane.ERROR_MESSAGE);
            }
        });
        gbc.gridx = 0;
        gbc.gridy = 10;
        gbc.gridwidth = 2;
        panel.add(catTcpEnableCheckBox, gbc);

        // Serial TX Control (OFF / RTS / DTR)
        gbc.gridx = 0;
        gbc.gridy = 11;
        gbc.gridwidth = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Serial TX Control:"), gbc);
        serialTxControlComboBox = new JComboBox<>(new String[]{"OFF", "RTS", "DTR"});
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(serialTxControlComboBox, gbc);
        if (pttPortComboBox != null) {
            pttPortComboBox.addActionListener(e -> {
                Object p = pttPortComboBox.getSelectedItem();
                boolean enable = extractSystemName(p) != null;
                serialTxControlComboBox.setEnabled(enable);
            });
        }

        // Auto Connect Checkbox
        autoConnectCheckBox = new JCheckBox("Auto Connect on Startup");
        autoConnectCheckBox.addActionListener(e -> {
            if (prefs != null) {
                prefs.putBoolean(PREF_AUTO_CONNECT, autoConnectCheckBox.isSelected());
            }
        });
        gbc.gridx = 0;
        gbc.gridy = 12;
        gbc.gridwidth = 2;
        panel.add(autoConnectCheckBox, gbc);

        // Info note about default Yaesu speed and recommendation
        JLabel yaesuBaudNote = new JLabel("Note: Default speed on Yaesu is 38400. I recommend changing to 115200 to avoid issues.");
        yaesuBaudNote.setFont(yaesuBaudNote.getFont().deriveFont(Font.ITALIC, yaesuBaudNote.getFont().getSize() - 1f));
        yaesuBaudNote.setForeground(Color.DARK_GRAY);
        gbc.gridx = 0;
        gbc.gridy = 13;
        gbc.gridwidth = 2;
        panel.add(yaesuBaudNote, gbc);

        return panel;
    }

    private JPanel createWaterfallSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Waterfall Settings"));

        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(Main.class);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Bandwidth Selection
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Display Bandwidth:"), gbc);

        bandwidthComboBox = new JComboBox<>(new String[]{"Full", "20 kHz", "10 kHz", "5 kHz", "3 kHz"});
        bandwidthComboBox.setSelectedItem(prefs.get("waterfallBandwidth", "3 kHz"));
        bandwidthComboBox.addActionListener(e -> {
            updateBandwidth();
            prefs.put("waterfallBandwidth", (String) bandwidthComboBox.getSelectedItem());
        });
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(bandwidthComboBox, gbc);

        // Color Theme Selection
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Color Theme:"), gbc);

        colorThemeComboBox = new JComboBox<>(new String[]{"Spectrum", "Fire", "Grayscale", "Ocean"});
        colorThemeComboBox.setSelectedItem(prefs.get("waterfallTheme", "Spectrum"));
        colorThemeComboBox.addActionListener(e -> {
            updateColorTheme();
            prefs.put("waterfallTheme", (String) colorThemeComboBox.getSelectedItem());
        });
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(colorThemeComboBox, gbc);

        // Resolution Slider
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Resolution (FFT Size):"), gbc);

        // Slider values: 0 to 100%
        resolutionSlider = new JSlider(0, 100, prefs.getInt("waterfallResolution", 100));
        resolutionSlider.setMajorTickSpacing(25);
        resolutionSlider.setMinorTickSpacing(5);
        resolutionSlider.setPaintTicks(true);

        java.util.Hashtable<Integer, JLabel> labelTable = new java.util.Hashtable<>();
        labelTable.put(0, new JLabel("0%"));
        labelTable.put(50, new JLabel("50%"));
        labelTable.put(100, new JLabel("100%"));
        resolutionSlider.setLabelTable(labelTable);
        resolutionSlider.setPaintLabels(true);

        resolutionSlider.addChangeListener(e -> {
            if (!resolutionSlider.getValueIsAdjusting()) {
                updateResolution();
                prefs.putInt("waterfallResolution", resolutionSlider.getValue());
            }
        });
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(resolutionSlider, gbc);

        // Speed Selection
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Speed:"), gbc);

        speedComboBox = new JComboBox<>(new String[]{"Fast (60 FPS)", "Normal (30 FPS)", "Slow (15 FPS)", "Very Slow (5 FPS)"});
        speedComboBox.setSelectedItem(prefs.get("waterfallSpeed", "Normal (30 FPS)"));
        speedComboBox.addActionListener(e -> {
            updateSpeed();
            prefs.put("waterfallSpeed", (String) speedComboBox.getSelectedItem());
        });
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(speedComboBox, gbc);

        // Multithreaded FFT option (replaces old GPU checkbox)
        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 2;
        fftMultithreadCheckBox = new JCheckBox("Enable Multithreaded FFTs (Waterfall)");
        fftMultithreadCheckBox.setToolTipText("When enabled, FFTs may run on multiple CPU threads for throughput. When disabled, FFTs run sequentially for perfect chronological order.");
        // Load preference
        boolean fftMultithread = prefs.getBoolean("fftMultithread", false);
        fftMultithreadCheckBox.setSelected(fftMultithread);

        fftMultithreadCheckBox.addActionListener(e -> {
            boolean enabled = fftMultithreadCheckBox.isSelected();
            prefs.putBoolean("fftMultithread", enabled);
            // Apply immediately if audio capture exists
            if (audioCapture != null) {
                int hw = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
                if (!enabled) hw = 1;
                try {
                    audioCapture.setFftWorkerThreads(hw);
                } catch (Throwable ignore) {}
            }
        });
        panel.add(fftMultithreadCheckBox, gbc);

        // Spacer
        gbc.gridy = 5;
        gbc.weighty = 1.0;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    private JPanel createDigitalSettingsPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Digital Mode Settings"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // Mode Selection
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        panel.add(new JLabel("Mode:"), gbc);

        digitalModeComboBox = new JComboBox<>(new String[]{
            "CW", "RTTY", "PSK"
        });
        digitalModeComboBox.addActionListener(e -> updateDigitalModeSettings());
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        panel.add(digitalModeComboBox, gbc);

        // RTTY tuning panel
        rttyTuningPanel = new JPanel(new GridBagLayout());
        rttyTuningPanel.setBorder(BorderFactory.createTitledBorder("RTTY Tuning"));
        GridBagConstraints rt = new GridBagConstraints();
        rt.insets = new Insets(2, 2, 2, 2);
        rt.fill = GridBagConstraints.HORIZONTAL;

        // Shift (Hz)
        rt.gridx = 0;
        rt.gridy = 0;
        rttyTuningPanel.add(new JLabel("Shift (Hz):"), rt);
        rttyShiftSpinner = new JSpinner(new SpinnerNumberModel(170, 50, 1000, 5));
        rt.gridx = 1;
        rttyTuningPanel.add(rttyShiftSpinner, rt);

        // Baud (Bd)
        rt.gridx = 0;
        rt.gridy = 1;
        rttyTuningPanel.add(new JLabel("Baud (Bd):"), rt);
        rttyBaudSpinner = new JSpinner(new SpinnerNumberModel(45.45, 20.0, 200.0, 0.05));
        rt.gridx = 1;
        rttyTuningPanel.add(rttyBaudSpinner, rt);

        // Invert
        rt.gridx = 0;
        rt.gridy = 2;
        rt.gridwidth = 2;
        rttyInvertCheckBox = new JCheckBox("Invert (Mark/Space)");
        rttyTuningPanel.add(rttyInvertCheckBox, rt);

        // Add RTTY tuning panel to main digital panel
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        panel.add(rttyTuningPanel, gbc);

        // PSK tuning panel
        pskTuningPanel = new JPanel(new GridBagLayout());
        pskTuningPanel.setBorder(BorderFactory.createTitledBorder("PSK Tuning"));
        GridBagConstraints pt = new GridBagConstraints();
        pt.insets = new Insets(2, 2, 2, 2);
        pt.fill = GridBagConstraints.HORIZONTAL;

        pt.gridx = 0;
        pt.gridy = 0;
        pskTuningPanel.add(new JLabel("Bandwidth (Hz):"), pt);
        // Default 31.25 Hz, allow 10..500 Hz
        pskBandwidthSpinner = new JSpinner(new SpinnerNumberModel(31.25, 10.0, 500.0, 1.0));
        // Load from preferences if present
        try {
            double savedBw = java.util.prefs.Preferences.userNodeForPackage(Main.class).getDouble("pskBandwidthHz", 31.25);
            pskBandwidthSpinner.setValue(savedBw);
        } catch (Exception ignored) {}
        pt.gridx = 1;
        pskTuningPanel.add(pskBandwidthSpinner, pt);

        // Squelch (%) row
        pt.gridx = 0;
        pt.gridy = 1;
        pskTuningPanel.add(new JLabel("Squelch (%):"), pt);
        pskSquelchSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 1));
        try {
            int savedSq = java.util.prefs.Preferences.userNodeForPackage(Main.class).getInt("pskSquelchPercent", 50);
            pskSquelchSpinner.setValue(savedSq);
        } catch (Exception ignored) {}
        pt.gridx = 1;
        pt.gridy = 1;
        pskTuningPanel.add(pskSquelchSpinner, pt);

        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        panel.add(pskTuningPanel, gbc);

        // CW tuning panel
        cwTuningPanel = new JPanel(new GridBagLayout());
        cwTuningPanel.setBorder(BorderFactory.createTitledBorder("CW Tuning"));
        GridBagConstraints ct = new GridBagConstraints();
        ct.insets = new Insets(2, 2, 2, 2);
        ct.fill = GridBagConstraints.HORIZONTAL;

        ct.gridx = 0;
        ct.gridy = 0;
        cwAutoWpmCheckBox = new JCheckBox("Auto WPM");
        cwAutoWpmCheckBox.setSelected(prefs.getBoolean("cwAutoWpm", true));
        cwTuningPanel.add(cwAutoWpmCheckBox, ct);

        ct.gridx = 0;
        ct.gridy = 1;
        cwTuningPanel.add(new JLabel("WPM:"), ct);
        cwWpmSpinner = new JSpinner(new SpinnerNumberModel(20, 5, 60, 1));
        cwWpmSpinner.setValue(prefs.getInt("cwWpm", 20));
        cwWpmSpinner.setEnabled(!cwAutoWpmCheckBox.isSelected());
        ct.gridx = 1;
        cwTuningPanel.add(cwWpmSpinner, ct);

        cwAutoWpmCheckBox.addActionListener(e -> cwWpmSpinner.setEnabled(!cwAutoWpmCheckBox.isSelected()));

        ct.gridx = 0;
        ct.gridy = 2;
        cwTuningPanel.add(new JLabel("Squelch (Mult):"), ct);
        cwSquelchSpinner = new JSpinner(new SpinnerNumberModel(2.0, 1.0, 10.0, 0.1));
        cwSquelchSpinner.setValue(prefs.getDouble("cwSquelch", 2.0));
        ct.gridx = 1;
        cwTuningPanel.add(cwSquelchSpinner, ct);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        panel.add(cwTuningPanel, gbc);

        // Listeners to apply changes live
        rttyShiftSpinner.addChangeListener(e -> applyRttySettingsToAll());
        rttyBaudSpinner.addChangeListener(e -> applyRttySettingsToAll());
        rttyInvertCheckBox.addActionListener(e -> applyRttySettingsToAll());
        pskBandwidthSpinner.addChangeListener(e -> applyPskSettingsToAll());
        pskSquelchSpinner.addChangeListener(e -> applyPskSettingsToAll());
        cwAutoWpmCheckBox.addActionListener(e -> applyCwSettingsToAll());
        cwWpmSpinner.addChangeListener(e -> applyCwSettingsToAll());
        cwSquelchSpinner.addChangeListener(e -> applyCwSettingsToAll());

        // Hide by default; shown when corresponding mode selected
        rttyTuningPanel.setVisible(false);
        pskTuningPanel.setVisible(false);
        cwTuningPanel.setVisible(false);

        // Spacer
        gbc.gridy = 4;
        gbc.weighty = 1.0;
        gbc.gridwidth = 1;
        panel.add(new JLabel(), gbc);

        return panel;
    }

    // Apply current RTTY UI settings to a decoder if it is an RTTY decoder
    private void applyRttySettingsIfApplicable(SignalDecoder d) {
        if (d instanceof RTTYDecoder rd) {
            Object shiftObj = rttyShiftSpinner.getValue();
            Object baudObj = rttyBaudSpinner.getValue();
            int shift = (shiftObj instanceof Number) ? ((Number) shiftObj).intValue() : 170;
            double baud = (baudObj instanceof Number) ? ((Number) baudObj).doubleValue() : 45.45;
            boolean inv = rttyInvertCheckBox.isSelected();
            rd.setShift(shift);
            rd.setBaudRate(baud);
            rd.setInverted(inv);
        }
    }

    // Apply current PSK UI settings to a decoder if it is a PSK31 decoder
    private void applyPskSettingsIfApplicable(SignalDecoder d) {
        if (d instanceof PSK31Decoder pd) {
            if (pskSquelchSpinner != null) {
                Object sqObj = pskSquelchSpinner.getValue();
                int sq = (sqObj instanceof Number) ? ((Number) sqObj).intValue() : 50;
                pd.setLockSquelchPercent(sq);
            }
        }
    }

    // Apply current CW UI settings to a decoder if it is a CWDecoder
    private void applyCwSettingsIfApplicable(SignalDecoder d) {
        if (d instanceof CWDecoder cd) {
            boolean auto = cwAutoWpmCheckBox.isSelected();
            cd.setAutoWpm(auto);
            if (!auto && cwWpmSpinner.getValue() instanceof Number) {
                cd.setWpm(((Number) cwWpmSpinner.getValue()).doubleValue());
            }
            if (cwSquelchSpinner.getValue() instanceof Number) {
                cd.setSquelch(((Number) cwSquelchSpinner.getValue()).doubleValue());
            }
        }
    }

    // Apply current PSK settings to all active decoders and update hover width
    private void applyPskSettingsToAll() {
        if (pskBandwidthSpinner == null) return;
        Object bwObj = pskBandwidthSpinner.getValue();
        double bw = (bwObj instanceof Number) ? ((Number) bwObj).doubleValue() : 31.25;
        int sq = 50;
        if (pskSquelchSpinner != null) {
            Object sqObj = pskSquelchSpinner.getValue();
            if (sqObj instanceof Number) sq = ((Number) sqObj).intValue();
        }
        // Persist preferences
        Preferences prefs = Preferences.userNodeForPackage(Main.class);
        prefs.putDouble("pskBandwidthHz", bw);
        prefs.putInt("pskSquelchPercent", sq);
        // Update hover width only if PSK31 is active mode
        String selectedMode = (String) digitalModeComboBox.getSelectedItem();
        if (waterfall != null && "PSK31".equals(selectedMode)) {
            waterfall.setHoverBandwidth(bw);
        }
        // Apply to default decoder
        if (decoder != null) {
            applyPskSettingsIfApplicable(decoder);
        }
        // Apply to all active decoders
        if (activeDecoders != null && !activeDecoders.isEmpty()) {
            for (SignalDecoder d : activeDecoders.values()) {
                applyPskSettingsIfApplicable(d);
            }
        }
    }

    // Apply RTTY settings to all active decoders and persist preferences
    private void applyRttySettingsToAll() {
        // Persist
        if (prefs != null && rttyShiftSpinner != null && rttyBaudSpinner != null && rttyInvertCheckBox != null) {
            Object shiftObj = rttyShiftSpinner.getValue();
            Object baudObj = rttyBaudSpinner.getValue();
            int shift = (shiftObj instanceof Number) ? ((Number) shiftObj).intValue() : 170;
            double baud = (baudObj instanceof Number) ? ((Number) baudObj).doubleValue() : 45.45;
            boolean inv = rttyInvertCheckBox.isSelected();
            prefs.putInt(PREF_RTTY_SHIFT, shift);
            prefs.putDouble(PREF_RTTY_BAUD, baud);
            prefs.putBoolean(PREF_RTTY_INVERT, inv);
        }
        // Apply to default decoder
        if (decoder != null) {
            applyRttySettingsIfApplicable(decoder);
        }
        // Apply to all active
        if (activeDecoders != null) {
            for (SignalDecoder d : activeDecoders.values()) {
                applyRttySettingsIfApplicable(d);
            }
        }
    }

    // Apply CW settings to all active decoders and persist preferences
    private void applyCwSettingsToAll() {
        if (prefs != null && cwAutoWpmCheckBox != null && cwWpmSpinner != null && cwSquelchSpinner != null) {
            prefs.putBoolean("cwAutoWpm", cwAutoWpmCheckBox.isSelected());
            if (cwWpmSpinner.getValue() instanceof Number) {
                prefs.putInt("cwWpm", ((Number) cwWpmSpinner.getValue()).intValue());
            }
            if (cwSquelchSpinner.getValue() instanceof Number) {
                prefs.putDouble("cwSquelch", ((Number) cwSquelchSpinner.getValue()).doubleValue());
            }
        }
        if (decoder != null) {
            applyCwSettingsIfApplicable(decoder);
        }
        if (activeDecoders != null) {
            for (SignalDecoder d : activeDecoders.values()) {
                applyCwSettingsIfApplicable(d);
            }
        }
    }

    // -------------------- Decoders Tab --------------------
    private JPanel createDecodersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Container that stacks decoder control boxes vertically
        decodersListPanel = new JPanel();
        decodersListPanel.setLayout(new BoxLayout(decodersListPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(decodersListPanel);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scroll, BorderLayout.CENTER);

        // Create one default decoder control box
        addDecoderBox();

        return panel;
    }

    private void addDecoderBox() {
        DecoderControlBox box = new DecoderControlBox();
        decoderBoxes.add(box);
        decodersListPanel.add(box.getPanel());
        decodersListPanel.revalidate();
        decodersListPanel.repaint();
        updateDecoderBoxes();
    }

    private void addDecoderBox(SpectrogramSelection sel) {
        if (sel == null || sel.getId() == null) return;
        DecoderControlBox box = new DecoderControlBox(sel);
        decoderBoxes.add(box);
        decoderBoxesById.put(sel.getId(), box);
        decodersListPanel.add(box.getPanel());
        // If nothing is active yet, make this one active
        if (decoderActiveGroup.getSelection() == null) {
            box.getActiveButton().setSelected(true);
        }
        decodersListPanel.revalidate();
        decodersListPanel.repaint();
        updateDecoderBoxes();
    }

    private void removeDecoderBox(DecoderControlBox box) {
        box.dispose();
        decoderActiveGroup.remove(box.getActiveButton());
        decoderBoxes.remove(box);
        decodersListPanel.remove(box.getPanel());
        decodersListPanel.revalidate();
        decodersListPanel.repaint();
    }

    private void updateDecoderBoxes() {
        for (DecoderControlBox b : decoderBoxes) {
            b.updateLabels();
        }
    }

    private void clearDecoderBoxes() {
        // Dispose timers and remove all decoder boxes from UI
        for (DecoderControlBox box : new java.util.ArrayList<>(decoderBoxes)) {
            removeDecoderBox(box);
        }
        decoderBoxesById.clear();
    }

    private void removeDecoderBoxById(String id) {
        if (id == null) return;
        DecoderControlBox b = decoderBoxesById.remove(id);
        if (b != null) {
            removeDecoderBox(b);
        }
    }

    // Lightweight horizontal meter that visualizes signal power relative to threshold.
    // Ratio range 0..2.0, with a fixed marker at 1.0 indicating the squelch trip point.
    private static class CwLevelMeter extends JComponent {
        private volatile double ratio = 0.0; // power/threshold
        CwLevelMeter() {
            setPreferredSize(new Dimension(120, 14));
            setMinimumSize(new Dimension(60, 12));
            setToolTipText("Signal/Threshold meter (1.0 = trip point)");
        }
        public void setRatio(double r) {
            double clamped = Math.max(0.0, Math.min(2.0, r));
            if (SwingUtilities.isEventDispatchThread()) {
                this.ratio = clamped;
                repaint();
            } else {
                SwingUtilities.invokeLater(() -> {
                    this.ratio = clamped;
                    repaint();
                });
            }
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Background
                g2.setColor(new Color(30, 30, 30));
                g2.fillRect(0, 0, w, h);
                // Border
                g2.setColor(new Color(80, 80, 80));
                g2.drawRect(0, 0, w - 1, h - 1);
                // Fill up to ratio
                double r = this.ratio;
                int fillW = (int) Math.round(Math.min(1.0, r / 2.0) * (w - 2)); // normalize 0..2 -> 0..1
                Color fill = (r >= 1.0) ? new Color(0, 160, 0) : new Color(70, 120, 200);
                g2.setColor(fill);
                g2.fillRect(1, 1, fillW, h - 2);
                // Threshold marker at 1.0 (half width)
                int xMarker = 1 + (w - 2) / 2;
                g2.setColor(new Color(220, 220, 60));
                g2.drawLine(xMarker, 1, xMarker, h - 2);
            } finally {
                g2.dispose();
            }
        }
    }

    private class DecoderControlBox {
        private final JPanel panel;
        private final JLabel modeLabel;
        private final JLabel freqLabel;
        private final JLabel timeLabel;
        private final JToggleButton activeButton;
        private final JButton deleteButton;
        private final javax.swing.Timer timer;
        private final SpectrogramSelection selection; // null for generic box
        // RTTY lock indicator UI
        private final JLabel lockTextLabel;
        private final JProgressBar lockBar;
        // CW per-decoder controls (per box)
        private JPanel cwControlPanel;
        private JCheckBox cwAutoCheckBox; // Auto WPM on/off
        private JCheckBox cwMeterEnableCheckBox; // Show/hide CW level meter (default: on)
        private JSlider cwWpmSlider;      // 5..60 WPM (horizontal)
        private JSlider cwSquelchSlider;  // 10..100 => 1.0..10.0 (horizontal)
        private JLabel cwWpmValueLabel;   // Shows current WPM (auto: live; manual: slider)
        private CwLevelMeter cwLevelMeter; // Visual meter: signal vs threshold (ratio)
        private CWDecoder attachedCwForMeter; // decoder currently feeding the meter
        // RTTY per-decoder controls (per box)
        private JPanel rttyControlPanel;
        private JSpinner rttyShiftSpinnerBox;
        private JSpinner rttyBaudSpinnerBox;
        private JCheckBox rttyInvertCheckBoxBox;
        private JSpinner rttySquelchSpinnerBox; // percent 0..100
        // PSK per-decoder controls (per box)
        private JPanel pskControlPanel;
        private JSpinner pskSquelchSpinnerBox; // percent 0..100

        DecoderControlBox() {
            this(null);
        }

        DecoderControlBox(SpectrogramSelection selection) {
            this.selection = selection;
            panel = new JPanel(new GridBagLayout());
            panel.setBorder(BorderFactory.createTitledBorder(getBoxTitle()));

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(4, 6, 4, 6);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill = GridBagConstraints.NONE;

            // Mode
            gbc.gridx = 0; gbc.gridy = 0;
            panel.add(new JLabel("Mode:"), gbc);
            modeLabel = new JLabel("-");
            modeLabel.setFont(modeLabel.getFont().deriveFont(Font.BOLD));
            gbc.gridx = 1; gbc.gridy = 0;
            panel.add(modeLabel, gbc);

            // Frequency
            gbc.gridx = 0; gbc.gridy = 1;
            panel.add(new JLabel("Frequency:"), gbc);
            freqLabel = new JLabel("-");
            gbc.gridx = 1; gbc.gridy = 1;
            panel.add(freqLabel, gbc);

            // Time (info)
            gbc.gridx = 0; gbc.gridy = 2;
            panel.add(new JLabel("Time:"), gbc);
            timeLabel = new JLabel("-");
            timeLabel.setFont(timeLabel.getFont().deriveFont(Font.ITALIC));
            gbc.gridx = 1; gbc.gridy = 2;
            panel.add(timeLabel, gbc);

            // RTTY Lock indicator (hidden by default)
            gbc.gridx = 0; gbc.gridy = 3;
            lockTextLabel = new JLabel("RTTY Lock:");
            panel.add(lockTextLabel, gbc);
            gbc.gridx = 1; gbc.gridy = 3; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
            lockBar = new JProgressBar(0, 100);
            lockBar.setStringPainted(true);
            lockBar.setValue(0);
            panel.add(lockBar, gbc);
            // Hidden unless an RTTY decoder is attached
            lockTextLabel.setVisible(false);
            lockBar.setVisible(false);
            gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0;

            // Spacer
            gbc.gridx = 0; gbc.gridy = 4; gbc.weightx = 1.0;
            panel.add(Box.createHorizontalStrut(1), gbc);

            // Active toggle
            activeButton = new JToggleButton("Active");
            decoderActiveGroup.add(activeButton);
            activeButton.addActionListener(e -> {
                if (activeButton.isSelected()) {
                    Main.setFooterText("Active decoder set to output");
                }
            });
            gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0;
            panel.add(activeButton, gbc);

            // Delete button
            deleteButton = new JButton("Delete");
            deleteButton.addActionListener(e -> {
                if (selection != null && waterfall != null) {
                    // Trigger standard removal flow via waterfall event
                    waterfall.removeSelection(selection);
                } else {
                    // Generic box not bound to a selection
                    removeDecoderBox(this);
                }
            });
            gbc.gridx = 2; gbc.gridy = 1;
            panel.add(deleteButton, gbc);

            // CW vertical controls (Auto, WPM, Squelch) and per-decoder PSK/RTTY panels
            buildCwControlPanel();
            buildRttyControlPanel();
            buildPskControlPanel();
            JPanel sideControls = new JPanel();
            sideControls.setLayout(new BoxLayout(sideControls, BoxLayout.Y_AXIS));
            sideControls.add(cwControlPanel);
            sideControls.add(Box.createVerticalStrut(6));
            sideControls.add(rttyControlPanel);
            sideControls.add(Box.createVerticalStrut(6));
            sideControls.add(pskControlPanel);
            gbc.gridx = 3; gbc.gridy = 0; gbc.gridheight = 5; gbc.fill = GridBagConstraints.VERTICAL;
            panel.add(sideControls, gbc);
            gbc.gridheight = 1; gbc.fill = GridBagConstraints.NONE;

            // Initialize per-decoder PSK/RTTY controls visibility
            updatePskRttyControlsVisibility();

            // Timer to update time label every second
            timer = new javax.swing.Timer(1000, e -> {
                updateTime();
                updateLockIndicator();
                updateCwControlsVisibility();
                updatePskRttyControlsVisibility();
                updateCwWpmValueLabel();
            });
            timer.setRepeats(true);
            timer.start();

            // Initialize labels
            updateLabels();
            updateTime();
            // Initialize lock indicator state
            updateLockIndicator();
            updateCwControlsVisibility();
        }

        private void updateTime() {
            LocalDateTime now = LocalDateTime.now();
            timeLabel.setText(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }

        private void updateLockIndicator() {
            // Resolve decoder tied to this control box
            SignalDecoder d = null;
            if (selection != null && activeDecoders != null) {
                d = activeDecoders.get(selection.getId());
            } else {
                d = decoder;
            }
            if (d instanceof RTTYDecoder rd) {
                int val = rd.getLockPercent();
                lockBar.setValue(val);
                lockBar.setString(val + "%");
                // Simple color coding
                Color fg;
                if (val >= 75) fg = new Color(0, 150, 0);
                else if (val >= 40) fg = new Color(200, 150, 0);
                else fg = new Color(170, 0, 0);
                lockBar.setForeground(fg);
                lockTextLabel.setText("RTTY Lock:");
                lockTextLabel.setVisible(true);
                lockBar.setVisible(true);
            } else if (d instanceof PSK31Decoder pd) {
                int val = pd.getLockPercent();
                lockBar.setValue(val);
                lockBar.setString(val + "%");
                Color fg;
                if (val >= 75) fg = new Color(0, 150, 0);
                else if (val >= 40) fg = new Color(200, 150, 0);
                else fg = new Color(170, 0, 0);
                lockBar.setForeground(fg);
                lockTextLabel.setText("PSK Lock:");
                lockTextLabel.setVisible(true);
                lockBar.setVisible(true);
            } else {
                lockTextLabel.setVisible(false);
                lockBar.setVisible(false);
            }
        }

        private void buildCwControlPanel() {
            // Meter component for CW signal vs threshold
            if (cwLevelMeter == null) cwLevelMeter = new CwLevelMeter();
            cwControlPanel = new JPanel(new GridBagLayout());
            cwControlPanel.setBorder(BorderFactory.createTitledBorder("CW"));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(2, 4, 2, 4);
            g.fill = GridBagConstraints.HORIZONTAL;
            g.weightx = 0.0;

            // Auto checkbox
            cwAutoCheckBox = new JCheckBox("Auto WPM");
            boolean autoPref = true;
            int wpmPref = 20;
            double squelchPref = 2.0;
            boolean meterPref = true;
            try {
                if (prefs != null) {
                    autoPref = prefs.getBoolean("cwAutoWpm", true);
                    wpmPref = prefs.getInt("cwWpm", 20);
                    squelchPref = prefs.getDouble("cwSquelch", 2.0);
                    meterPref = prefs.getBoolean("cwMeterEnabled", true);
                }
            } catch (Exception ignored) {}
            cwAutoCheckBox.setSelected(autoPref);
            g.gridx = 0; g.gridy = 0; g.gridwidth = 3;
            cwControlPanel.add(cwAutoCheckBox, g);

            // WPM row
            g.gridwidth = 1;
            JLabel wpmLabel = new JLabel("WPM:");
            g.gridx = 0; g.gridy = 1; g.weightx = 0.0;
            cwControlPanel.add(wpmLabel, g);

            cwWpmSlider = new JSlider(JSlider.HORIZONTAL, 5, 60, Math.max(5, Math.min(60, wpmPref)));
            cwWpmSlider.setPaintTicks(true);
            cwWpmSlider.setMajorTickSpacing(5);
            cwWpmSlider.setMinorTickSpacing(1);
            g.gridx = 1; g.gridy = 1; g.weightx = 1.0;
            cwControlPanel.add(cwWpmSlider, g);

            cwWpmValueLabel = new JLabel("" + cwWpmSlider.getValue());
            g.gridx = 2; g.gridy = 1; g.weightx = 0.0;
            cwControlPanel.add(cwWpmValueLabel, g);

            // Squelch row
            JLabel sqLabel = new JLabel("Squelch:");
            g.gridx = 0; g.gridy = 2; g.weightx = 0.0;
            cwControlPanel.add(sqLabel, g);

            cwSquelchSlider = new JSlider(JSlider.HORIZONTAL, 10, 100, (int) Math.round(Math.max(10, Math.min(100, squelchPref * 10.0))));
            cwSquelchSlider.setPaintTicks(true);
            cwSquelchSlider.setMajorTickSpacing(10);
            cwSquelchSlider.setMinorTickSpacing(1);
            cwSquelchSlider.setToolTipText("x1.0..x10.0");
            g.gridx = 1; g.gridy = 2; g.weightx = 1.0;
            cwControlPanel.add(cwSquelchSlider, g);

            JLabel sqVal = new JLabel(String.format("x%.1f", cwSquelchSlider.getValue() / 10.0));
            g.gridx = 2; g.gridy = 2; g.weightx = 0.0;
            cwControlPanel.add(sqVal, g);

            // Meter enable checkbox
            cwMeterEnableCheckBox = new JCheckBox("Show meter");
            cwMeterEnableCheckBox.setToolTipText("Display CW signal vs threshold meter");
            cwMeterEnableCheckBox.setSelected(meterPref);
            g.gridx = 0; g.gridy = 3; g.gridwidth = 3; g.weightx = 0.0;
            cwControlPanel.add(cwMeterEnableCheckBox, g);
            g.gridwidth = 1;

            // Meter row (Signal vs Threshold)
            JLabel meterLabel = new JLabel("Signal/Thresh:");
            g.gridx = 0; g.gridy = 4; g.weightx = 0.0;
            cwControlPanel.add(meterLabel, g);
            g.gridx = 1; g.gridy = 4; g.weightx = 1.0; g.gridwidth = 2;
            cwLevelMeter.setVisible(meterPref);
            cwControlPanel.add(cwLevelMeter, g);
            g.gridwidth = 1;

            // Initial enable state
            cwWpmSlider.setEnabled(!cwAutoCheckBox.isSelected());

            // Listeners
            ActionListener autoListener = e -> {
                boolean auto = cwAutoCheckBox.isSelected();
                cwWpmSlider.setEnabled(!auto);
                // Push to decoder
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (dLocal instanceof CWDecoder) {
                    ((CWDecoder) dLocal).setAutoWpm(auto);
                }
                // Persist
                if (prefs != null) {
                    prefs.putBoolean("cwAutoWpm", auto);
                }
                updateCwWpmValueLabel();
            };
            cwAutoCheckBox.addActionListener(autoListener);

            javax.swing.event.ChangeListener wpmListener = e -> {
                // Push to decoder only when manual
                if (!cwAutoCheckBox.isSelected()) {
                    SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                    if (dLocal instanceof CWDecoder) {
                        ((CWDecoder) dLocal).setWpm(cwWpmSlider.getValue());
                    }
                    if (prefs != null) {
                        prefs.putInt("cwWpm", cwWpmSlider.getValue());
                    }
                }
                updateCwWpmValueLabel();
            };
            cwWpmSlider.addChangeListener(wpmListener);

            javax.swing.event.ChangeListener sqListener = e -> {
                double sq = cwSquelchSlider.getValue() / 10.0;
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (dLocal instanceof CWDecoder) {
                    ((CWDecoder) dLocal).setSquelch(sq);
                }
                if (prefs != null) {
                    prefs.putDouble("cwSquelch", sq);
                }
                sqVal.setText(String.format("x%.1f", sq));
            };
            cwSquelchSlider.addChangeListener(sqListener);

            // Meter toggle listener
            ActionListener meterToggle = e2 -> {
                boolean enabled = cwMeterEnableCheckBox.isSelected();
                if (cwLevelMeter != null) cwLevelMeter.setVisible(enabled);
                // Persist
                try { if (prefs != null) prefs.putBoolean("cwMeterEnabled", enabled); } catch (Throwable ignore) {}
                // Attach/detach listener to reduce overhead
                if (enabled) {
                    attachCwMeterToCurrentDecoder();
                } else {
                    // Detach from any currently attached decoder
                    if (attachedCwForMeter != null) {
                        try { attachedCwForMeter.setLevelListener(null); } catch (Throwable ignore) {}
                        attachedCwForMeter = null;
                    }
                }
                // Relayout panel to reflect visibility change
                try { cwControlPanel.revalidate(); cwControlPanel.repaint(); } catch (Throwable ignore) {}
            };
            cwMeterEnableCheckBox.addActionListener(meterToggle);
        }

        private void updateCwWpmValueLabel() {
            if (cwWpmValueLabel == null) return;
            boolean auto = cwAutoCheckBox != null && cwAutoCheckBox.isSelected();
            if (auto) {
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (dLocal instanceof CWDecoder cd) {
                    double cur = cd.getCurrentWpm();
                    cwWpmValueLabel.setText(String.format("%.1f (auto)", cur));
                } else {
                    cwWpmValueLabel.setText("auto");
                }
            } else {
                cwWpmValueLabel.setText(String.valueOf(cwWpmSlider.getValue()));
            }
        }

        private void updateCwControlsVisibility() {
            // Show CW controls only when the actual decoder instance is CW for this box
            SignalDecoder d = null;
            if (selection != null && activeDecoders != null) {
                d = activeDecoders.get(selection.getId());
            } else {
                d = decoder;
            }
            boolean isCw = d instanceof CWDecoder;
            cwControlPanel.setVisible(isCw);
            // Keep meter attached to the current CW decoder if available
            attachCwMeterToCurrentDecoder();
        }

        private void attachCwMeterToCurrentDecoder() {
            // If meter is disabled in UI, ensure no listener is attached
            boolean meterOn = (cwMeterEnableCheckBox == null) || cwMeterEnableCheckBox.isSelected();
            SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
            CWDecoder cd = (dLocal instanceof CWDecoder) ? (CWDecoder) dLocal : null;

            if (!meterOn) {
                if (attachedCwForMeter != null) {
                    try { attachedCwForMeter.setLevelListener(null); } catch (Throwable ignore) {}
                    attachedCwForMeter = null;
                }
                if (cwLevelMeter != null) cwLevelMeter.setRatio(0.0);
                return;
            }

            if (attachedCwForMeter == cd) return; // already correct
            // Detach previous
            if (attachedCwForMeter != null) {
                try { attachedCwForMeter.setLevelListener(null); } catch (Throwable ignore) {}
                attachedCwForMeter = null;
            }
            if (cd != null) {
                attachedCwForMeter = cd;
                try {
                    cd.setLevelListener(sample -> {
                        if (cwLevelMeter != null) cwLevelMeter.setRatio(sample.ratio);
                    });
                } catch (Throwable ignore) {}
            } else {
                if (cwLevelMeter != null) cwLevelMeter.setRatio(0.0);
            }
        }

        private void updatePskRttyControlsVisibility() {
            // Show PSK/RTTY controls based on the actual decoder instance bound to this box
            SignalDecoder d;
            if (selection != null && activeDecoders != null) {
                d = activeDecoders.get(selection.getId());
            } else {
                d = decoder;
            }
            boolean isRtty = d instanceof RTTYDecoder;
            boolean isPsk = d instanceof PSK31Decoder;
            if (rttyControlPanel != null) rttyControlPanel.setVisible(isRtty);
            if (pskControlPanel != null) pskControlPanel.setVisible(isPsk);
        }

        // Snapshot recent audio for autodetection
        private float[] getRecentAudioSnapshotMillis(int msRequested) {
            synchronized (recentAudioLock) {
                if (recentAudioBuf == null || recentAudioBuf.length == 0 || recentAudioSampleRate <= 0) return null;
                int sr = recentAudioSampleRate;
                int need = Math.max(1, Math.min(recentAudioBuf.length, (int) Math.round(sr * (msRequested / 1000.0))));
                float[] out = new float[need];
                int cap = recentAudioBuf.length;
                int end = recentAudioPos; // write position for next sample
                int start = end - need;
                while (start < 0) start += cap;
                if (start + need <= cap) {
                    System.arraycopy(recentAudioBuf, start, out, 0, need);
                } else {
                    int first = cap - start;
                    System.arraycopy(recentAudioBuf, start, out, 0, first);
                    System.arraycopy(recentAudioBuf, 0, out, first, need - first);
                }
                return out;
            }
        }

        private void buildRttyControlPanel() {
            rttyControlPanel = new JPanel(new GridBagLayout());
            rttyControlPanel.setBorder(BorderFactory.createTitledBorder("RTTY"));
            GridBagConstraints rt = new GridBagConstraints();
            rt.insets = new Insets(2, 4, 2, 4);
            rt.fill = GridBagConstraints.HORIZONTAL;
            rt.weightx = 1.0;

            int defShift = 170;
            double defBaud = 45.45;
            boolean defInv = false;
            int defSq = 50;
            try {
                if (prefs != null) {
                    defShift = prefs.getInt(PREF_RTTY_SHIFT, defShift);
                    defBaud = prefs.getDouble(PREF_RTTY_BAUD, defBaud);
                    defInv = prefs.getBoolean(PREF_RTTY_INVERT, defInv);
                }
            } catch (Exception ignored) {}

            // Shift
            rt.gridx = 0; rt.gridy = 0; rt.weightx = 0.0;
            rttyControlPanel.add(new JLabel("Shift (Hz):"), rt);
            rttyShiftSpinnerBox = new JSpinner(new SpinnerNumberModel(defShift, 50, 1000, 5));
            rt.gridx = 1; rt.gridy = 0; rt.weightx = 1.0;
            rttyControlPanel.add(rttyShiftSpinnerBox, rt);

            // Baud
            rt.gridx = 0; rt.gridy = 1; rt.weightx = 0.0;
            rttyControlPanel.add(new JLabel("Baud (Bd):"), rt);
            rttyBaudSpinnerBox = new JSpinner(new SpinnerNumberModel(defBaud, 20.0, 200.0, 0.05));
            rt.gridx = 1; rt.gridy = 1; rt.weightx = 1.0;
            rttyControlPanel.add(rttyBaudSpinnerBox, rt);

            // Invert
            rt.gridx = 0; rt.gridy = 2; rt.gridwidth = 2;
            rttyInvertCheckBoxBox = new JCheckBox("Invert (Mark/Space)");
            rttyInvertCheckBoxBox.setSelected(defInv);
            rttyControlPanel.add(rttyInvertCheckBoxBox, rt);
            rt.gridwidth = 1;

            // Squelch
            rt.gridx = 0; rt.gridy = 3; rt.weightx = 0.0;
            rttyControlPanel.add(new JLabel("Squelch (%):"), rt);
            rttySquelchSpinnerBox = new JSpinner(new SpinnerNumberModel(defSq, 0, 100, 1));
            rt.gridx = 1; rt.gridy = 3; rt.weightx = 1.0;
            rttyControlPanel.add(rttySquelchSpinnerBox, rt);

            // Listeners: apply to bound decoder only
            final int defShift0 = defShift;
            final double defBaud0 = defBaud;
            final int defSq0 = defSq;
            javax.swing.event.ChangeListener apply = e -> {
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (dLocal instanceof RTTYDecoder rd) {
                    Object sObj = rttyShiftSpinnerBox.getValue();
                    Object bObj = rttyBaudSpinnerBox.getValue();
                    int s = (sObj instanceof Number) ? ((Number) sObj).intValue() : defShift0;
                    double b = (bObj instanceof Number) ? ((Number) bObj).doubleValue() : defBaud0;
                    boolean inv = rttyInvertCheckBoxBox.isSelected();
                    Object sqObj = rttySquelchSpinnerBox.getValue();
                    int sq = (sqObj instanceof Number) ? ((Number) sqObj).intValue() : defSq0;
                    rd.setShift(s);
                    rd.setBaudRate(b);
                    rd.setInverted(inv);
                    rd.setLockSquelchPercent(sq);
                }
            };
            rttyShiftSpinnerBox.addChangeListener(apply);
            rttyBaudSpinnerBox.addChangeListener(apply);
            rttyInvertCheckBoxBox.addActionListener(e -> apply.stateChanged(null));
            rttySquelchSpinnerBox.addChangeListener(apply);

            // Autodetect Button
            rt.gridx = 0; rt.gridy = 4; rt.gridwidth = 2; rt.weightx = 1.0;
            JButton rttyAutoBtn = new JButton("Autodetect");
            rttyControlPanel.add(rttyAutoBtn, rt);
            rt.gridwidth = 1;

            rttyAutoBtn.addActionListener(e -> {
                // Determine bound decoder and selection
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (!(dLocal instanceof RTTYDecoder rd)) {
                    JOptionPane.showMessageDialog(DeviceListPanel.this, "RTTY decoder not active for this selection.", "Autodetect", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                // Grab ~1200 ms of recent audio
                float[] trial;
                int srLocal;
                synchronized (recentAudioLock) {
                    srLocal = recentAudioSampleRate;
                }
                trial = getRecentAudioSnapshotMillis(1200);
                if (trial == null || trial.length < Math.max(1024, srLocal / 4)) {
                    JOptionPane.showMessageDialog(DeviceListPanel.this, "Not enough recent audio captured yet. Try again in a second while signal is present.", "Autodetect", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                // Run detection off-EDT
                new Thread(() -> {
                    double detectedCenterAbs = -1;
                    int detectedShift = -1;
                    try {
                        Waterfall wf = (waterfall instanceof Waterfall) ? (Waterfall) waterfall : null;
                        SpectrogramSelection sel = selection;
                        if (wf != null && sel != null) {
                            RttyAutoDetector.Est est = RttyAutoDetector.estimateCenterAndShift(wf, sel);
                            if (est != null && est.ok) {
                                detectedCenterAbs = est.centerHzAbs;
                                detectedShift = est.shiftHz;
                            }
                        }
                        // If estimation failed, fall back to current UI/selection values
                        if (detectedShift <= 0) {
                            Object sObj = rttyShiftSpinnerBox.getValue();
                            detectedShift = (sObj instanceof Number) ? ((Number) sObj).intValue() : defShift0;
                        }
                        // Compute audio Hz center relative to current band start
                        double centerAudioHz;
                        try {
                            double selCenterAbs = (selection != null) ? (selection.getStartFrequency() + selection.getBandwidth() / 2.0) : 0.0;
                            long base = (waterfall instanceof Waterfall) ? ((Waterfall) waterfall).getStartFrequency() : 0L;
                            centerAudioHz = Math.max(0.0, selCenterAbs - base);
                        } catch (Throwable t1) {
                            centerAudioHz = 2100.0; // fallback
                        }
                        if (detectedCenterAbs > 0 && (waterfall instanceof Waterfall)) {
                            try {
                                long base = ((Waterfall) waterfall).getStartFrequency();
                                centerAudioHz = Math.max(0, detectedCenterAbs - base);
                            } catch (Throwable ignore2) {}
                        }
                        // Score candidates
                        RttyAutoDetector.Candidate best = RttyAutoDetector.autodetectParams(rd, trial, srLocal, centerAudioHz, detectedShift);
                        if (best == null) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(DeviceListPanel.this, "Autodetect could not lock. Ensure a steady RTTY signal is present.", "Autodetect", JOptionPane.WARNING_MESSAGE));
                            return;
                        }
                        final double fCenterAbsFinal;
                        if (detectedCenterAbs > 0) {
                            fCenterAbsFinal = detectedCenterAbs;
                        } else {
                            double tmpCenter = 0.0;
                            try {
                                if (selection != null) tmpCenter = selection.getStartFrequency() + selection.getBandwidth() / 2.0;
                            } catch (Throwable ignore4) {}
                            fCenterAbsFinal = tmpCenter;
                        }
                        final double fCenterAudio = centerAudioHz;
                        final int fShift = best.shiftHz;
                        final double fBaud = best.baud;
                        final boolean fInv = best.inverted;
                        final int fScore = best.lockScore;
                        // Apply on EDT
                        SwingUtilities.invokeLater(() -> {
                            try {
                                // Apply to decoder
                                rd.setTargetFrequency((int) Math.round(fCenterAudio));
                                rd.setShift(fShift);
                                rd.setBaudRate(fBaud);
                                rd.setInverted(fInv);
                                // Keep bandpass aligned with current marker width (or a safe default)
                                try { rd.setBandpassWidthHz(Math.max(0.0, (selection != null ? selection.getBandwidth() : (fShift * 2.0)))); } catch (Throwable ignore) {}
                                // Update UI controls
                                rttyShiftSpinnerBox.setValue(fShift);
                                rttyBaudSpinnerBox.setValue(fBaud);
                                rttyInvertCheckBoxBox.setSelected(fInv);
                                // Optionally move selection center visually
                                if (waterfall instanceof Waterfall && selection != null && fCenterAbsFinal > 0) {
                                    try { ((Waterfall) waterfall).moveSelectionCenter(selection, fCenterAbsFinal); } catch (Throwable ignore3) {}
                                }
                                // Notify in chat
                                if (chatBoxPanel != null) {
                                    String msg = String.format("RTTY auto: shift=%d Hz, baud=%.2f, inv=%s, lock≈%d%%", fShift, fBaud, fInv ? "ON" : "OFF", fScore);
                                    if (selection != null) {
                                        chatBoxPanel.appendTextTo(selection.getId(), msg + "\n", "RTTY", (selection.getColor() != null ? selection.getColor() : Color.WHITE));
                                    } else {
                                        chatBoxPanel.appendText(msg + "\n");
                                    }
                                }
                            } catch (Throwable t) {
                                JOptionPane.showMessageDialog(DeviceListPanel.this, "Failed to apply autodetected parameters: " + t.getMessage(), "Autodetect", JOptionPane.ERROR_MESSAGE);
                            }
                        });
                    } catch (Throwable t) {
                        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(DeviceListPanel.this, "Autodetect error: " + t.getMessage(), "Autodetect", JOptionPane.ERROR_MESSAGE));
                    }
                }, "RTTY-Autodetect").start();
            });

            // Hide by default; visibility updates per attached decoder
            rttyControlPanel.setVisible(false);
        }

        private void buildPskControlPanel() {
            pskControlPanel = new JPanel(new GridBagLayout());
            pskControlPanel.setBorder(BorderFactory.createTitledBorder("PSK"));
            GridBagConstraints pt = new GridBagConstraints();
            pt.insets = new Insets(2, 4, 2, 4);
            pt.fill = GridBagConstraints.HORIZONTAL;
            pt.weightx = 1.0;

            int defSq = 50;
            try {
                defSq = java.util.prefs.Preferences.userNodeForPackage(Main.class).getInt("pskSquelchPercent", 50);
            } catch (Exception ignored) {}

            pt.gridx = 0; pt.gridy = 0; pt.weightx = 0.0;
            pskControlPanel.add(new JLabel("Squelch (%):"), pt);
            pskSquelchSpinnerBox = new JSpinner(new SpinnerNumberModel(defSq, 0, 100, 1));
            pt.gridx = 1; pt.gridy = 0; pt.weightx = 1.0;
            pskControlPanel.add(pskSquelchSpinnerBox, pt);

            javax.swing.event.ChangeListener apply = e -> {
                SignalDecoder dLocal = (selection != null && activeDecoders != null) ? activeDecoders.get(selection.getId()) : decoder;
                if (dLocal instanceof PSK31Decoder pd) {
                    Object sqObj = pskSquelchSpinnerBox.getValue();
                    int sq = (sqObj instanceof Number) ? ((Number) sqObj).intValue() : 50;
                    pd.setLockSquelchPercent(sq);
                }
            };
            pskSquelchSpinnerBox.addChangeListener(apply);

            pskControlPanel.setVisible(false);
        }

        void updateLabels() {
            // Prefer the per-selection mode (persistent per marker); fall back to current global only for generic boxes
            String mode = "-";
            if (selection != null && selection.getModeName() != null && !selection.getModeName().isEmpty()) {
                mode = selection.getModeName();
            } else if (digitalModeComboBox != null && digitalModeComboBox.getSelectedItem() != null) {
                mode = digitalModeComboBox.getSelectedItem().toString();
            }
            modeLabel.setText(mode);
            freqLabel.setText(formatFrequency(currentFrequency));
        }

        JPanel getPanel() { return panel; }
        JToggleButton getActiveButton() { return activeButton; }
        void dispose() { 
            if (attachedCwForMeter != null) {
                try { attachedCwForMeter.setLevelListener(null); } catch (Throwable ignore) {}
                attachedCwForMeter = null;
            }
            if (timer != null) timer.stop(); 
        }

        private String getBoxTitle() {
            if (selection != null) {
                String id = selection.getId();
                if (id != null && !id.isEmpty()) {
                    return "Decoder [" + id + "]";
                }
                return "Decoder (selection)";
            }
            return "Decoder";
        }
    }

    private JPanel createRadioControlPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Radio Control"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Frequency Display
        frequencyDisplay = new JLabel("--- Hz");
        frequencyDisplay.setFont(new Font("Monospaced", Font.BOLD, 24));
        frequencyDisplay.setHorizontalAlignment(SwingConstants.CENTER);
        frequencyDisplay.setBorder(BorderFactory.createLineBorder(Color.GRAY));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4;
        panel.add(frequencyDisplay, gbc);

        // Meters Panel (Moved to top)
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 4;
        JPanel meterContainer = new JPanel(new BorderLayout());
        JPanel meterPanel = new JPanel(new GridLayout(3, 4, 5, 5)); // 3 rows, 4 cols (Label, Meter, Label, Meter)

        Dimension meterDim = new Dimension(150, 30);

        // S-Meter (RM1)
        sMeter = new TrackingProgressBar(0, 255);
        sMeter.setPreferredSize(meterDim);
        sMeter.setPrefix("S-Meter");
        meterPanel.add(new JLabel("S-Meter:"));
        meterPanel.add(sMeter);

        // Power Meter (RM2)
        powerMeter = new TrackingProgressBar(0, 255);
        powerMeter.setPreferredSize(meterDim);
        powerMeter.setPrefix("RF Power");
        powerMeter.setTrackMin(true);
        powerMeter.setTrackAvg(true);
        meterPanel.add(new JLabel("RF Power:"));
        meterPanel.add(powerMeter);

        // ALC Meter (RM3)
        alcMeter = new TrackingProgressBar(0, 255);
        alcMeter.setPreferredSize(meterDim);
        alcMeter.setPrefix("ALC");
        alcMeter.setTrackAvg(true);
        alcMeter.setShowLastAsBlue(true);
        alcMeter.setActiveHoldMillis(1000);
        alcMeter.setLastMarkerColor(new Color(13, 71, 161));
        meterPanel.add(new JLabel("ALC:"));
        meterPanel.add(alcMeter);

        // SWR Meter (RM4)
        swrMeter = new TrackingProgressBar(0, 255);
        swrMeter.setPreferredSize(meterDim);
        swrMeter.setPrefix("SWR");
        swrMeter.setShowLastAsBlue(true);
        swrMeter.setActiveHoldMillis(1000);
        swrMeter.setLastMarkerColor(new Color(13, 71, 161));
        // Keep formatter but we won't track min when using last-value marker
        swrMeter.setTrackMin(false);
        swrMeter.setValueFormatter(val -> String.format("%.2f", 1.0 + (val / 50.0)));
        meterPanel.add(new JLabel("SWR:"));
        meterPanel.add(swrMeter);

        // ID Meter (RM5)
        idMeter = new TrackingProgressBar(0, 255);
        idMeter.setPreferredSize(meterDim);
        idMeter.setPrefix("ID");
        idMeter.setShowLastAsBlue(true);
        idMeter.setActiveHoldMillis(1000);
        idMeter.setLastMarkerColor(new Color(13, 71, 161));
        idMeter.setTrackMin(false);
        idMeter.setTrackAvg(true);
        idMeter.setValueFormatter(val -> String.format("%.2f A", val * 0.17));
        meterPanel.add(new JLabel("ID:"));
        meterPanel.add(idMeter);

        // VDD Meter (RM6)
        vddMeter = new TrackingProgressBar(0, 255);
        vddMeter.setPreferredSize(meterDim);
        vddMeter.setPrefix("VDD");
        vddMeter.setShowLastAsBlue(true);
        vddMeter.setActiveHoldMillis(1000);
        vddMeter.setLastMarkerColor(new Color(13, 71, 161));
        vddMeter.setTrackMin(false);
        vddMeter.setTrackAvg(true);
        vddMeter.setValueFormatter(val -> String.format("%.1f V", val / 10.0));
        meterPanel.add(new JLabel("VDD:"));
        meterPanel.add(vddMeter);

        meterContainer.add(meterPanel, BorderLayout.CENTER);

        JButton resetMaxButton = new JButton("Reset Meters");
        resetMaxButton.addActionListener(e -> {
            if (sMeter != null) sMeter.reset();
            if (powerMeter != null) powerMeter.reset();
            if (alcMeter != null) alcMeter.reset();
            if (swrMeter != null) swrMeter.reset();
            if (idMeter != null) idMeter.reset();
            if (vddMeter != null) vddMeter.reset();
        });
        JPanel resetPanel = new JPanel(new BorderLayout());
        resetPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        resetPanel.add(resetMaxButton, BorderLayout.CENTER);
        meterContainer.add(resetPanel, BorderLayout.SOUTH);

        panel.add(meterContainer, gbc);

        // Initialize meter markers so they are visible without requiring a manual reset
        if (sMeter != null) sMeter.reset();
        if (powerMeter != null) powerMeter.reset();
        if (alcMeter != null) alcMeter.reset();
        if (swrMeter != null) swrMeter.reset();
        if (idMeter != null) idMeter.reset();
        if (vddMeter != null) vddMeter.reset();

        // Frequency Adjustment Buttons — two rows: increment on top, decrement below; columns aligned by magnitude
        JPanel freqControls = new JPanel(new BorderLayout(8, 0));

        // Define steps in ascending magnitude
        long[] stepsAll = {1, 10, 100, 1000, 10000, 100000, 1000000};
        String[] labelsAll = {"1 Hz", "10 Hz", "100 Hz", "1 kHz", "10 kHz", "100 kHz", "1 MHz"};

        // Grid: + row then - row
        JPanel stepsGrid = new JPanel(new GridLayout(2, stepsAll.length, 5, 5));

        // Top row: increments
        for (int i = 0; i < stepsAll.length; i++) {
            long step = stepsAll[i];
            JButton b = new JButton("+" + labelsAll[i]);
            b.setToolTipText("Increase frequency by " + labelsAll[i]);
            b.addActionListener(e -> adjustFrequency(step));
            stepsGrid.add(b);
        }

        // Bottom row: decrements
        for (int i = 0; i < stepsAll.length; i++) {
            long step = stepsAll[i];
            JButton b = new JButton("-" + labelsAll[i]);
            b.setToolTipText("Decrease frequency by " + labelsAll[i]);
            b.addActionListener(e -> adjustFrequency(-step));
            stepsGrid.add(b);
        }

        freqControls.add(stepsGrid, BorderLayout.CENTER);

        // Rounding buttons stacked vertically on the right
        JPanel roundingPanel = new JPanel();
        roundingPanel.setLayout(new BoxLayout(roundingPanel, BoxLayout.Y_AXIS));
        JButton roundHzZeroBtn = new JButton("Hz→0");
        roundHzZeroBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        roundHzZeroBtn.setToolTipText("Round Hz part down to 0 (set …,xxx.000 Hz)");
        roundHzZeroBtn.addActionListener(e -> {
            long f = currentFrequency;
            if (f <= 0) return;
            long target = (f / 1000L) * 1000L; // drop Hz remainder
            if (target < 0) target = 0;
            rcSetFrequency(target);
        });
        JButton roundHz500Btn = new JButton("Hz→500");
        roundHz500Btn.setAlignmentX(Component.LEFT_ALIGNMENT);
        roundHz500Btn.setToolTipText("Round Hz part to 500 (set …,xxx.500 Hz)");
        roundHz500Btn.addActionListener(e -> {
            long f = currentFrequency;
            if (f <= 0) return;
            long base = (f / 1000L) * 1000L;
            long target = base + 500L;
            if (target < 0) target = 0;
            rcSetFrequency(target);
        });
        roundingPanel.add(roundHzZeroBtn);
        roundingPanel.add(Box.createVerticalStrut(3));
        roundingPanel.add(roundHz500Btn);

        freqControls.add(roundingPanel, BorderLayout.EAST);

        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 4;
        panel.add(freqControls, gbc);


        // RF Power Slider
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 1;
        panel.add(new JLabel("RF Power:"), gbc);

        rfPowerValueLabel = new JLabel("50%");
        rfPowerSlider = new JSlider(0, 100, 50);
        rfPowerSlider.setMajorTickSpacing(25);
        rfPowerSlider.setPaintTicks(true);
        rfPowerSlider.setPaintLabels(true);
        rfPowerSlider.setMinimumSize(new Dimension(50, rfPowerSlider.getPreferredSize().height));
        rfPowerSlider.addChangeListener(e -> {
            rfPowerValueLabel.setText(rfPowerSlider.getValue() + "%");
            if (!rfPowerSlider.getValueIsAdjusting() && radioControl != null && !updatingRFPower) {
                radioControl.setRFPower(rfPowerSlider.getValue());
            }
        });
        gbc.gridx = 1; gbc.gridwidth = 2;
        panel.add(rfPowerSlider, gbc);

        gbc.gridx = 3; gbc.gridwidth = 1;
        panel.add(rfPowerValueLabel, gbc);

        // Stacked Controls under RF Power (sliders vertically, buttons beneath)
        JPanel controlsColumn = new JPanel();
        controlsColumn.setLayout(new BoxLayout(controlsColumn, BoxLayout.Y_AXIS));
        controlsColumn.setBorder(BorderFactory.createEmptyBorder());

        // AF Gain (horizontal slider stacked vertically)

        JPanel afPanel = new JPanel(new BorderLayout(4, 2));
        afPanel.add(new JLabel("AF"), BorderLayout.WEST);
        afGainSlider = new JSlider(JSlider.HORIZONTAL, 0, 255, 125);
        afGainSlider.setMinimumSize(new Dimension(50, afGainSlider.getPreferredSize().height));
        afGainSlider.addChangeListener(e -> {
            int val = afGainSlider.getValue();
            // Reflect volume in footer immediately while sliding
            try { Main.setFooterVolume((int) Math.round(val * 100.0 / 255.0)); } catch (Throwable ignore) {}
            if (!afGainSlider.getValueIsAdjusting() && radioControl != null && !updatingRadioSliders) {
                radioControl.setAFGain(val);
            }
        });
        afPanel.add(afGainSlider, BorderLayout.CENTER);
        controlsColumn.add(afPanel);

        // RF Gain (horizontal slider stacked vertically)

        JPanel rfPanel = new JPanel(new BorderLayout(4, 2));
        rfPanel.add(new JLabel("RF"), BorderLayout.WEST);
        rfGainSlider = new JSlider(JSlider.HORIZONTAL, 0, 255, 255);
        rfGainSlider.setMinimumSize(new Dimension(50, rfGainSlider.getPreferredSize().height));
        rfGainSlider.addChangeListener(e -> {
            if (!rfGainSlider.getValueIsAdjusting() && radioControl != null && !updatingRadioSliders) {
                radioControl.setRFGain(rfGainSlider.getValue());
            }
        });
        rfPanel.add(rfGainSlider, BorderLayout.CENTER);
        controlsColumn.add(rfPanel);

        // IF Width (horizontal slider stacked vertically)

        JPanel widthPanel = new JPanel(new BorderLayout(4, 2));
        widthPanel.add(new JLabel("Width"), BorderLayout.WEST);
        widthSlider = new JSlider(JSlider.HORIZONTAL, 0, 23, 15);
        widthSlider.setMajorTickSpacing(5);
        widthSlider.setPaintTicks(true);
        widthSlider.setMinimumSize(new Dimension(50, widthSlider.getPreferredSize().height));
        widthSlider.addChangeListener(e -> {
            if (!widthSlider.getValueIsAdjusting() && radioControl != null && !updatingRadioSliders) {
                radioControl.setWidth(widthSlider.getValue());
            }
        });
        widthPanel.add(widthSlider, BorderLayout.CENTER);
        controlsColumn.add(widthPanel);

        // Buttons group (NB, NR, PROC) under the sliders
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JToggleButton nbButton = new JToggleButton("NB");
        nbButton.addActionListener(e -> { if (radioControl != null) radioControl.setNoiseBlanker(nbButton.isSelected()); });
        JToggleButton nrButton = new JToggleButton("NR");
        nrButton.addActionListener(e -> { if (radioControl != null) radioControl.setNoiseReduction(nrButton.isSelected()); });
        JToggleButton procButton = new JToggleButton("PROC");
        procButton.addActionListener(e -> { if (radioControl != null) radioControl.setSpeechProcessor(procButton.isSelected()); });
        btnPanel.add(nbButton);
        btnPanel.add(nrButton);
        btnPanel.add(procButton);
        controlsColumn.add(btnPanel);

        // Add controls column directly (no inner split) to ensure it resizes with the panel
        controlsColumn.setMinimumSize(new Dimension(100, 80)); // a bit taller to fit stacked sliders

        gbc.gridx = 0; gbc.gridy = 5; gbc.gridwidth = 4; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0;
        panel.add(controlsColumn, gbc);

        // VOX Button (toggle) with yellow LED indicator
        // Create small LED icons for off/on states
        Icon ledOff = new Icon() {
            private final int d = 12;
            @Override public int getIconWidth() { return d; }
            @Override public int getIconHeight() { return d; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // Outer ring
                    g2.setColor(new Color(40, 40, 40));
                    g2.fillOval(x, y, d, d);
                    // Inner dark LED
                    g2.setColor(new Color(90, 90, 90));
                    g2.fillOval(x + 2, y + 2, d - 4, d - 4);
                    // Gloss
                    g2.setColor(new Color(255, 255, 255, 40));
                    g2.fillOval(x + 2, y + 2, d - 6, (d - 6) / 2);
                    // Border
                    g2.setColor(new Color(20, 20, 20));
                    g2.drawOval(x, y, d - 1, d - 1);
                } finally {
                    g2.dispose();
                }
            }
        };
        Icon ledOn = new Icon() {
            private final int d = 12;
            @Override public int getIconWidth() { return d; }
            @Override public int getIconHeight() { return d; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // Outer ring
                    g2.setColor(new Color(60, 60, 30));
                    g2.fillOval(x, y, d, d);
                    // Bright yellow LED
                    g2.setColor(new Color(255, 215, 0));
                    g2.fillOval(x + 2, y + 2, d - 4, d - 4);
                    // Glow highlight
                    g2.setPaint(new RadialGradientPaint(
                            new Point(x + d / 2, y + d / 2), d / 2f,
                            new float[]{0f, 1f},
                            new Color[]{new Color(255, 255, 180, 200), new Color(255, 215, 0, 0)}
                    ));
                    g2.fillOval(x, y, d, d);
                    // Gloss
                    g2.setColor(new Color(255, 255, 255, 60));
                    g2.fillOval(x + 2, y + 2, d - 6, (d - 6) / 2);
                    // Border
                    g2.setColor(new Color(50, 50, 25));
                    g2.drawOval(x, y, d - 1, d - 1);
                } finally {
                    g2.dispose();
                }
            }
        };

        voxButton = new JToggleButton("VOX");
        voxButton.setIcon(ledOff);
        voxButton.setSelectedIcon(ledOn);
        voxButton.setHorizontalTextPosition(SwingConstants.RIGHT);
        voxButton.setIconTextGap(8);
        voxButton.setFocusPainted(false);
        voxButton.addActionListener(e -> {
            if (updatingVox) return; // prevent feedback loop
            if (radioControl != null) {
                radioControl.setVOX(voxButton.isSelected());
            } else {
                try { getRadioControl().setVOX(voxButton.isSelected()); } catch (Exception ignored) {}
            }
        });
        // Disable VOX automatically when the radio reports a Data mode
        try {
            RadioControl rc = getRadioControl();
            rc.setDataModeListener(inData -> SwingUtilities.invokeLater(() -> {
                boolean data = inData != null && inData;
                voxButton.setEnabled(!data);
                String tip = data ? "VOX not supported in current Data mode. Switch to USB/LSB/AM to enable." : "Toggle VOX (voice-activated transmit)";
                voxButton.setToolTipText(tip);
                if (data && voxButton.isSelected()) {
                    // If VOX is ON while entering data mode, reflect OFF in UI (radio may ignore VX1; in data)
                    voxButton.setSelected(false);
                }
            }));
        } catch (Throwable t) { /* ignore */ }
        gbc.gridx = 0; gbc.gridy = 6; gbc.gridwidth = 2;
        panel.add(voxButton, gbc);

        // Tune Button
        tuneButton = new JToggleButton("TUNE");
        JCheckBox atasCheckBox = new JCheckBox("ATAS");
        java.util.prefs.Preferences prefs = java.util.prefs.Preferences.userNodeForPackage(Main.class);
        atasCheckBox.setSelected(prefs.getBoolean("atasEnabled", false));
        atasCheckBox.addActionListener(evt -> prefs.putBoolean("atasEnabled", atasCheckBox.isSelected()));

        tuneButton.addActionListener(e -> {
            if (radioControl != null) {
                boolean wantTune = tuneButton.isSelected();
                // Allow tuning in any mode, regardless of voice boundary
                if (atasCheckBox.isSelected()) {
                    radioControl.setATASTune(wantTune);
                } else {
                    radioControl.setTune(wantTune);
                }
            }
        });
        gbc.gridx = 2; gbc.gridy = 6; gbc.gridwidth = 1;
        panel.add(tuneButton, gbc);

        gbc.gridx = 3; gbc.gridy = 6; gbc.gridwidth = 1;
        panel.add(atasCheckBox, gbc);

        // VFO Selection
        gbc.gridx = 0; gbc.gridy = 7; gbc.gridwidth = 1; gbc.weighty = 0.0;
        panel.add(new JLabel("VFO:"), gbc);

        ButtonGroup vfoGroup = new ButtonGroup();
        vfoAButton = new JToggleButton("A");
        vfoBButton = new JToggleButton("B");
        vfoGroup.add(vfoAButton);
        vfoGroup.add(vfoBButton);

        // Use ItemListeners so we act only when a button becomes SELECTED.
        java.awt.event.ItemListener vfoItemListenerA = evt -> {
            if (isUpdatingUI) return;
            if (evt.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                rcSetActiveVFO('A');
            }
        };
        java.awt.event.ItemListener vfoItemListenerB = evt -> {
            if (isUpdatingUI) return;
            if (evt.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                rcSetActiveVFO('B');
            }
        };
        vfoAButton.addItemListener(vfoItemListenerA);
        vfoBButton.addItemListener(vfoItemListenerB);

        // Default VFO selection: A
        isUpdatingUI = true;
        try {
            vfoAButton.setSelected(true);
        } finally {
            isUpdatingUI = false;
        }

        gbc.gridx = 1; gbc.gridwidth = 1;
        panel.add(vfoAButton, gbc);
        gbc.gridx = 2;
        panel.add(vfoBButton, gbc);

        // Split toggle on same row
        splitToggle = new JToggleButton("SPLIT");
        splitToggle.setToolTipText("Receive on active VFO, Transmit on the other VFO");
        splitToggle.addActionListener(e -> {
            if (isUpdatingUI) return;
            if (radioControl != null) {
                if (splitToggle.isSelected()) radioControl.enableSplit();
                else radioControl.disableSplit();
            }
            SwingUtilities.invokeLater(this::refreshFrequencyLabel);
        });
        gbc.gridx = 3;
        panel.add(splitToggle, gbc);

        // 1 MHz Band Slider
        gbc.gridy = 1; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weighty = 0.0;
        panel.add(new JLabel("1 MHz Band:"), gbc);
        JPanel bandPanel = new JPanel(new BorderLayout(5, 2));
        band1MHzSlider = new JSlider(0, 1_000_000, 0);
        band1MHzSlider.setPaintTicks(true);
        band1MHzSlider.setMajorTickSpacing(100_000);
        band1MHzSlider.setMinorTickSpacing(50_000);
        band1MHzSlider.setPaintLabels(true);
        band1MHzSlider.setMinimumSize(new Dimension(50, band1MHzSlider.getPreferredSize().height));
        band1MHzSlider.addChangeListener(e2 -> {
            if (isUpdatingUI) return;
            long base = bandBaseHz;
            int val = band1MHzSlider.getValue();
            long newFreq = base + (long) val;
            // Keep tooltip in sync while dragging
            band1MHzSlider.setToolTipText(formatFrequency(newFreq));
            // Only send CAT when the user releases the slider to avoid feedback loops/rebasing fights
            if (!band1MHzSlider.getValueIsAdjusting()) {
                rcSetFrequency(newFreq);
                try { if (prefs != null) prefs.putInt(PREF_BAND1MHZ_OFFSET, val); } catch (Throwable ignore) {}
            }
        });
        // Restore last band offset within 1 MHz, if available
        try {
            if (prefs != null) {
                int savedOffset = prefs.getInt(PREF_BAND1MHZ_OFFSET, -1);
                if (savedOffset >= 0) {
                    boolean prev = isUpdatingUI; isUpdatingUI = true;
                    band1MHzSlider.setValue(Math.max(0, Math.min(1_000_000, savedOffset)));
                    isUpdatingUI = prev;
                }
            }
        } catch (Throwable ignore) {}
        bandPanel.add(band1MHzSlider, BorderLayout.CENTER);
        band1MHzLabel = new JLabel("");
        bandPanel.add(band1MHzLabel, BorderLayout.SOUTH);
        gbc.gridx = 1; gbc.gridwidth = 3;
        panel.add(bandPanel, gbc);

        // Mode Selection
        gbc.gridx = 0; gbc.gridy = 9; gbc.gridwidth = 1; gbc.weighty = 0.0;
        panel.add(new JLabel("Mode:"), gbc);

        modeComboBox = new JComboBox<>(MODE_LABELS);
        modeComboBox.addActionListener(e -> {
            if (isUpdatingUI) return; // Skip if we are updating from radio
            Object sel = modeComboBox.getSelectedItem();
            if (sel != null) {
                rcSetModeByLabel(sel.toString());
            }
        });
        gbc.gridx = 1; gbc.gridwidth = 3;
        panel.add(modeComboBox, gbc);

        // CW quick actions row
        gbc.gridy = 10; gbc.gridx = 0; gbc.gridwidth = 1;
        panel.add(new JLabel("CW:"), gbc);
        JButton sendCwCallBtn = new JButton("Send Callsign (CW)");
        sendCwCallBtn.setToolTipText("Keys radio and sends your callsign in CW using audio");
        sendCwCallBtn.addActionListener(e -> sendCallsignCWThreaded());
        gbc.gridx = 1; gbc.gridwidth = 3;
        panel.add(sendCwCallBtn, gbc);


        // VFO Sync Buttons Row (moved below VFO section)
        gbc.gridy = 8; gbc.gridx = 0; gbc.gridwidth = 1;
        panel.add(new JLabel("VFO Sync:"), gbc);
        JPanel syncPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 5, 0));
        JButton copyAToBBtn = new JButton("A→B");
        copyAToBBtn.setToolTipText("Copy VFO-A to VFO-B (AB;)");
        copyAToBBtn.addActionListener(e -> { if (radioControl != null) radioControl.copyAtoB(); });
        JButton copyBToABtn = new JButton("B→A");
        copyBToABtn.setToolTipText("Copy VFO-B to VFO-A (BA;)");
        copyBToABtn.addActionListener(e -> { if (radioControl != null) radioControl.copyBtoA(); });
        JButton swapVfoBtn = new JButton("Swap");
        swapVfoBtn.setToolTipText("Swap VFO-A and VFO-B (SV;)");
        swapVfoBtn.addActionListener(e -> { if (radioControl != null) radioControl.swapVFOs(); });
        syncPanel.add(copyAToBBtn);
        syncPanel.add(copyBToABtn);
        syncPanel.add(swapVfoBtn);
        gbc.gridx = 1; gbc.gridwidth = 3;
        panel.add(syncPanel, gbc);



        // Bottom bar: Volume, Mute, Sample Rate
        gbc.gridy = 11; gbc.gridx = 0; gbc.gridwidth = 4; gbc.weighty = 0.0;
        JPanel bottomBar = new JPanel(new BorderLayout(8, 0));

        // Left: Volume + Mute
        JPanel volPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        volPanel.add(new JLabel("Volume:"));
        int initPct = 100;
        try {
            if (audioPlayback != null) initPct = (int) Math.round(audioPlayback.getOutputGain() * 100.0);
        } catch (Throwable ignore) {}
        // Restore saved volume percent if available
        try {
            if (prefs != null) initPct = Math.max(0, Math.min(100, prefs.getInt(PREF_APP_VOLUME_PCT, initPct)));
        } catch (Throwable ignore) {}
        appVolumeSlider = new JSlider(0, 100, initPct);
        appVolumeSlider.setPreferredSize(new Dimension(200, appVolumeSlider.getPreferredSize().height));
        appVolumeSlider.addChangeListener(e -> {
            if (updatingAppVolume) return;
            int pct = appVolumeSlider.getValue();
            lastNonMutedVolumePct = pct;
            // Persist immediately
            try { if (prefs != null) prefs.putInt(PREF_APP_VOLUME_PCT, pct); } catch (Throwable ignore) {}
            // Update playback gain immediately
            try { if (audioPlayback != null) audioPlayback.setOutputGain(Math.max(0.0, Math.min(1.0, pct / 100.0))); } catch (Throwable ignore) {}
            // Update footer live while sliding
            try { Main.setFooterVolume(pct); } catch (Throwable ignore) {}
        });
        volPanel.add(appVolumeSlider);
        muteCheckBox = new JCheckBox("Mute");
        // Restore saved mute state
        boolean startMuted = false;
        try { if (prefs != null) startMuted = prefs.getBoolean(PREF_APP_MUTED, false); } catch (Throwable ignore) {}
        muteCheckBox.setSelected(startMuted);
        if (startMuted) {
            try { if (audioPlayback != null) audioPlayback.setMuted(true); } catch (Throwable ignore) {}
            updatingAppVolume = true;
            try { appVolumeSlider.setEnabled(false); appVolumeSlider.setValue(0); } finally { updatingAppVolume = false; }
            try { Main.setFooterVolume(0); } catch (Throwable ignore) {}
        } else {
            try { if (audioPlayback != null) audioPlayback.setOutputGain(initPct / 100.0); } catch (Throwable ignore) {}
            try { Main.setFooterVolume(initPct); } catch (Throwable ignore) {}
        }
        muteCheckBox.addActionListener(e -> {
            boolean mute = muteCheckBox.isSelected();
            try { if (prefs != null) prefs.putBoolean(PREF_APP_MUTED, mute); } catch (Throwable ignore) {}
            if (mute) {
                // Save current volume percent and set mute
                lastNonMutedVolumePct = appVolumeSlider != null ? appVolumeSlider.getValue() : lastNonMutedVolumePct;
                if (audioPlayback != null) audioPlayback.setMuted(true);
                if (appVolumeSlider != null) {
                    updatingAppVolume = true;
                    try { appVolumeSlider.setEnabled(false); appVolumeSlider.setValue(0); } finally { updatingAppVolume = false; }
                }
                try { Main.setFooterVolume(0); } catch (Throwable ignore) {}
            } else {
                int restorePct = Math.max(0, Math.min(100, lastNonMutedVolumePct));
                if (audioPlayback != null) {
                    audioPlayback.setMuted(false);
                    audioPlayback.setOutputGain(restorePct / 100.0);
                }
                if (appVolumeSlider != null) {
                    updatingAppVolume = true;
                    try {
                        appVolumeSlider.setEnabled(true);
                        appVolumeSlider.setValue(restorePct);
                    } finally { updatingAppVolume = false; }
                }
                try { Main.setFooterVolume(restorePct); } catch (Throwable ignore) {}
            }
        });
        volPanel.add(muteCheckBox);
        bottomBar.add(volPanel, BorderLayout.WEST);

        // Right: Sample rate label
        JPanel srPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        sampleRateLabel = new JLabel("Sample Rate: N/A");
        srPanel.add(sampleRateLabel);
        bottomBar.add(srPanel, BorderLayout.EAST);

        panel.add(bottomBar, gbc);

        // Filler
        gbc.gridx = 0; gbc.gridy = 12; gbc.weighty = 1.0;
        panel.add(new JPanel(), gbc);

        return panel;
    }

    private void adjustFrequency(long delta) {
        // Only send the CAT command to the active backend; do not update local display directly.
        long target = (currentFrequency > 0) ? currentFrequency + delta : delta;
        rcSetFrequency(target);
    }

    private void refreshFrequencyLabel() {
        if (frequencyDisplay == null) return;
        String prefix = (activeVfo == 'B') ? "B: " : "A: ";
        String split = (splitToggle != null && splitToggle.isSelected()) ? " [SPLIT]" : "";
        frequencyDisplay.setText(prefix + formatFrequency(currentFrequency) + split);
        // Update decoder panels to reflect new frequency
        updateDecoderBoxes();
        // Update the 1 MHz band slider/label
        updateBandSliderFromFrequency();
    }

    private void updateBandSliderFromFrequency() {
        if (band1MHzSlider == null || band1MHzLabel == null) return;
        long freq = currentFrequency;
        if (freq <= 0) return;
        long base = (freq / 1_000_000L) * 1_000_000L;
        long offset = freq - base;
        long endBase = base + 1_000_000L;
        String startLabel = String.format("%d.%03d MHz", base / 1_000_000L, 0L);
        String endLabel = String.format("%d.%03d MHz", endBase / 1_000_000L, 0L);
        String midLabel = String.format("%d.%03d MHz", (base + 500_000L) / 1_000_000L, ((base + 500_000L) % 1_000_000L) / 1_000L);
        String nowLabel = formatFrequency(freq);
        isUpdatingUI = true;
        try {
            bandBaseHz = base;
            if (band1MHzSlider.getMaximum() != 1_000_000) band1MHzSlider.setMaximum(1_000_000);
            if (band1MHzSlider.getMinimum() != 0) band1MHzSlider.setMinimum(0);
            int val = (int) Math.max(0L, Math.min(1_000_000L, offset));
            band1MHzSlider.setValue(val);
            band1MHzSlider.setToolTipText(nowLabel);
            rebuildBandSliderLabels();
            // Only show current frequency here; the scale is provided by tick labels now
            band1MHzLabel.setText("now " + nowLabel);
        } finally {
            isUpdatingUI = false;
        }
    }

    // Build slider label table aligned to the slider's scale (0..1,000,000)
    private void rebuildBandSliderLabels() {
        if (band1MHzSlider == null) return;
        java.util.Hashtable<Integer, javax.swing.JComponent> table = new java.util.Hashtable<>();
        for (int v = 0; v <= 1_000_000; v += 100_000) {
            long hz = bandBaseHz + v;
            String txt = String.format("%d.%03d", hz / 1_000_000L, (hz % 1_000_000L) / 1_000L);
            JLabel lbl = new JLabel(txt, SwingConstants.CENTER);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
            table.put(v, lbl);
        }
        band1MHzSlider.setLabelTable(table);
        band1MHzSlider.repaint();
    }

    private String formatFrequency(long hz) {
        long mhz = hz / 1_000_000;
        long khz = (hz % 1_000_000) / 1_000;
        long h = hz % 1_000;
        return String.format("%d.%03d.%03d Hz", mhz, khz, h);
    }

    private static long parseFrequencyHz(String text) throws NumberFormatException {
        if (text == null) throw new NumberFormatException("null");
        String s = text.trim().toUpperCase().replace(",", "");
        if (s.isEmpty()) throw new NumberFormatException("empty");
        if (s.endsWith("HZ")) s = s.substring(0, s.length() - 2).trim();
        if (s.endsWith("MHZ")) {
            double mhz = Double.parseDouble(s.substring(0, s.length() - 3).trim());
            return Math.round(mhz * 1_000_000.0);
        }
        if (s.endsWith("KHZ")) {
            double khz = Double.parseDouble(s.substring(0, s.length() - 3).trim());
            return Math.round(khz * 1_000.0);
        }
        if (s.contains(".")) {
            // Treat as MHz with decimal
            double mhz = Double.parseDouble(s);
            return Math.round(mhz * 1_000_000.0);
        }
        // Plain number: assume Hz if >= 1e6, else assume kHz
        long val = Long.parseLong(s);
        if (val < 100_000) {
            return val * 1000L;
        }
        return val;
    }

    // Build a user-friendly display label for a COM port while keeping the system name as the prefix
    private static String makePortLabel(SerialPort port) {
        String sys = safe(port::getSystemPortName);
        String desc = safe(port::getPortDescription);
        String dname = safe(port::getDescriptivePortName);
        String sn = safe(port::getSerialNumber);
        String pretty = sys != null ? sys : "";
        String tail = (dname != null && !dname.isBlank()) ? dname : desc;
        if (tail != null && !tail.isBlank()) pretty = pretty + " — " + tail;
        if (sn != null && !sn.isBlank()) pretty = pretty + "  [" + sn + "]";
        return pretty;
    }

    // Extract the actual system port name (e.g., "COM3") from a combo-box item
    private static String extractSystemName(Object item) {
        if (item == null) return null;
        String s = item.toString();
        if (s.startsWith("No Serial")) return null;
        if (HAMLIB_OPTION_LABEL.equals(s)) return null;
        int cut = s.length();
        int idxDash = s.indexOf('—');
        int idxSpace = s.indexOf(' ');
        if (idxDash >= 0) cut = Math.min(cut, idxDash);
        if (idxSpace >= 0) cut = Math.min(cut, idxSpace);
        return s.substring(0, cut).trim();
    }

    private static String safe(java.util.function.Supplier<String> s) {
        try { return s.get(); } catch (Throwable ignore) { return null; }
    }

    private void refreshDeviceLists() {
        // Refresh Serial Ports
        serialPortComboBox.removeAllItems();
        if (pttPortComboBox != null) pttPortComboBox.removeAllItems();

        serialPortComboBox.addItem("No Serial Connection");
        // Add Hamlib option to allow TCP rig control via rigctld
        serialPortComboBox.addItem(HAMLIB_OPTION_LABEL);
        if (pttPortComboBox != null) pttPortComboBox.addItem("No Serial Connection");

        try {
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort port : ports) {
                String label = makePortLabel(port);
                serialPortComboBox.addItem(label);
                if (pttPortComboBox != null) pttPortComboBox.addItem(label);
            }
        } catch (Throwable t) {
            // fallback to previous simple names if anything goes wrong
            List<String> ports = DeviceManager.getSerialPorts();
            for (String port : ports) {
                serialPortComboBox.addItem(port);
                if (pttPortComboBox != null) pttPortComboBox.addItem(port);
            }
        }
        serialPortComboBox.setEnabled(true);
        if (pttPortComboBox != null) pttPortComboBox.setEnabled(true);

        // Refresh Audio Inputs
        audioInputComboBox.removeAllItems();
        List<String> inputs = DeviceManager.getAudioInputDevices();
        if (inputs.isEmpty()) {
            audioInputComboBox.addItem("No Audio Inputs Found");
            audioInputComboBox.setEnabled(false);
        } else {
            for (String input : inputs) {
                audioInputComboBox.addItem(input);
            }
            audioInputComboBox.setEnabled(true);
        }

        // Refresh Audio Outputs
        audioOutputComboBox.removeAllItems();
        List<String> outputs = DeviceManager.getAudioOutputDevices();
        if (outputs.isEmpty()) {
            audioOutputComboBox.addItem("No Audio Outputs Found");
            audioOutputComboBox.setEnabled(false);
        } else {
            for (String output : outputs) {
                audioOutputComboBox.addItem(output);
            }
            audioOutputComboBox.setEnabled(true);
        }
    }

    private void updateSampleRateLabel() {
        try {
            int srIn = (audioCapture != null) ? audioCapture.getSampleRate() : -1;
            int srEff = (audioCapture != null) ? audioCapture.getEffectiveSampleRate() : -1;
            String text;
            if (srIn > 0 && srEff > 0 && srEff != srIn) {
                text = String.format("Sample Rate: %d Hz (eff %d Hz)", srIn, srEff);
            } else if (srIn > 0) {
                text = String.format("Sample Rate: %d Hz", srIn);
            } else if (audioPlayback != null && audioPlayback.getSampleRate() > 0) {
                text = String.format("Sample Rate: %d Hz (TX)", audioPlayback.getSampleRate());
            } else {
                text = "Sample Rate: N/A";
            }
            if (sampleRateLabel != null) sampleRateLabel.setText(text);
        } catch (Throwable ignore) {}
    }

    private void updateBandwidth() {
        if (waterfall == null || audioCapture == null) return;

        String selected = (String) bandwidthComboBox.getSelectedItem();
        int bandwidth = 0;
        int sampleRate = audioCapture.getSampleRate();

        if ("Full".equals(selected)) {
            bandwidth = sampleRate / 2;
        } else {
            // Parse "20 kHz" -> 20000
            String num = selected.split(" ")[0];
            bandwidth = Integer.parseInt(num) * 1000;
        }

        waterfall.setFrequencies(bandwidth);
        audioCapture.setProcessingBandwidth(bandwidth);

        // Update waterfall input sample rate to match effective rate (after decimation)
        waterfall.setInputSampleRate(audioCapture.getEffectiveSampleRate());
        // Update label to reflect any effective rate change
        updateSampleRateLabel();
    }

    private void updateColorTheme() {
        if (waterfall == null) return;
        String theme = (String) colorThemeComboBox.getSelectedItem();
        waterfall.setColorTheme(theme);
    }

    private void updateSpeed() {
        if (audioCapture == null) return;
        String selected = (String) speedComboBox.getSelectedItem();
        if (selected == null) return;
        int fps = 30;
        if (selected.contains("60")) fps = 60;
        else if (selected.contains("30")) fps = 30;
        else if (selected.contains("15")) fps = 15;
        else if (selected.contains("5")) fps = 5;

        audioCapture.setTargetFps(fps);
    }

    private void updateResolution() {
        if (audioCapture == null) return;
        int val = resolutionSlider.getValue();

        // Map 0-100 to exponent 9-14 (512 to 16384)
        double exponent = 9 + (val / 100.0) * 5;
        int fftSize = 1 << (int)Math.round(exponent);

        audioCapture.setFftSize(fftSize);
    }

    private void loadSettings() {
        prefs = Preferences.userNodeForPackage(DeviceListPanel.class);

        // Load Serial Port
        String lastPortName = prefs.get(PREF_SERIAL_PORT, "");
        String lastSerialNumber = prefs.get(PREF_SERIAL_PORT_SERIAL_NUMBER, "");

        boolean portFound = false;

        // First try to find by system name (prefix of label)
        if (!lastPortName.isEmpty()) {
            for (int i = 0; i < serialPortComboBox.getItemCount(); i++) {
                Object item = serialPortComboBox.getItemAt(i);
                String sys = extractSystemName(item);
                if (lastPortName.equals(sys)) {
                    serialPortComboBox.setSelectedIndex(i);
                    portFound = true;
                    break;
                }
            }
        }

        // If not found by name, try by serial number
        if (!portFound && !lastSerialNumber.isEmpty()) {
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort port : ports) {
                if (lastSerialNumber.equals(port.getSerialNumber())) {
                    String foundPortName = port.getSystemPortName();
                    // Check if this port is in the combobox
                    for (int i = 0; i < serialPortComboBox.getItemCount(); i++) {
                        Object item = serialPortComboBox.getItemAt(i);
                        String sys = extractSystemName(item);
                        if (foundPortName.equals(sys)) {
                            serialPortComboBox.setSelectedIndex(i);
                            portFound = true;
                            // Update the saved name so next time it might be faster
                            prefs.put(PREF_SERIAL_PORT, foundPortName);
                            break;
                        }
                    }
                }
                if (portFound) break;
            }
        }

        // Default to "No Serial Connection" if saved port not found
        if (!portFound && serialPortComboBox.getItemCount() > 0) {
            serialPortComboBox.setSelectedIndex(0);
        }

        // Load PTT Port (Standard)
        if (pttPortComboBox != null) {
            String lastPttName = prefs.get(PREF_PTT_PORT, "");
            String lastPttSerial = prefs.get(PREF_PTT_PORT_SERIAL_NUMBER, "");
            boolean pttFound = false;
            if (!lastPttName.isEmpty()) {
                for (int i = 0; i < pttPortComboBox.getItemCount(); i++) {
                    Object item = pttPortComboBox.getItemAt(i);
                    String sys = extractSystemName(item);
                    if (lastPttName.equals(sys)) {
                        pttPortComboBox.setSelectedIndex(i);
                        pttFound = true;
                        break;
                    }
                }
            }
            if (!pttFound && !lastPttSerial.isEmpty()) {
                SerialPort[] ports = SerialPort.getCommPorts();
                for (SerialPort port : ports) {
                    if (lastPttSerial.equals(port.getSerialNumber())) {
                        String foundName = port.getSystemPortName();
                        for (int i = 0; i < pttPortComboBox.getItemCount(); i++) {
                            Object item = pttPortComboBox.getItemAt(i);
                            String sys = extractSystemName(item);
                            if (foundName.equals(sys)) {
                                pttPortComboBox.setSelectedIndex(i);
                                pttFound = true;
                                prefs.put(PREF_PTT_PORT, foundName);
                                break;
                            }
                        }
                    }
                    if (pttFound) break;
                }
            }
            if (!pttFound && pttPortComboBox.getItemCount() > 0) {
                pttPortComboBox.setSelectedIndex(0);
            }
        }

        baudRateComboBox.setSelectedItem(prefs.getInt(PREF_BAUD_RATE, 38400));
        dataBitsComboBox.setSelectedItem(prefs.getInt(PREF_DATA_BITS, 8));
        stopBitsComboBox.setSelectedItem(prefs.get(PREF_STOP_BITS, "1"));
        parityComboBox.setSelectedItem(prefs.get(PREF_PARITY, "None"));
        flowControlComboBox.setSelectedItem(prefs.get(PREF_FLOW_CONTROL, "RTS/CTS"));

        // Load Serial TX Control
        if (serialTxControlComboBox != null) {
            String ctrl = prefs.get(PREF_SERIAL_TX_CONTROL, "OFF");
            if (!"OFF".equals(ctrl) && !"RTS".equals(ctrl) && !"DTR".equals(ctrl)) ctrl = "OFF";
            serialTxControlComboBox.setSelectedItem(ctrl);
            Object pttSelObj = pttPortComboBox != null ? pttPortComboBox.getSelectedItem() : null;
            boolean enable = extractSystemName(pttSelObj) != null;
            serialTxControlComboBox.setEnabled(enable);
        }

        // Prefer restoring by strict hardware identity if available
        String savedInputId = prefs.get(PREF_AUDIO_INPUT_ID, "");
        boolean inputSelected = false;
        if (savedInputId != null && !savedInputId.isEmpty()) {
            try {
                Mixer.Info[] infos = AudioSystem.getMixerInfo();
                for (Mixer.Info mi : infos) {
                    try {
                        Mixer m = AudioSystem.getMixer(mi);
                        if (!m.isLineSupported(new Line.Info(TargetDataLine.class))) continue; // input only
                        String id = new AudioDeviceWatcher.Identity(mi.getName(), mi.getVendor(), mi.getDescription(), mi.getVersion()).toString();
                        if (savedInputId.equals(id)) {
                            String name = mi.getName();
                            for (int i = 0; i < audioInputComboBox.getItemCount(); i++) {
                                if (name.equals(audioInputComboBox.getItemAt(i))) {
                                    audioInputComboBox.setSelectedIndex(i);
                                    inputSelected = true;
                                    break;
                                }
                            }
                            break;
                        }
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
        }
        if (!inputSelected) {
            // Fallback: restore by last known display name
            String lastInput = prefs.get(PREF_AUDIO_INPUT, "");
            if (!lastInput.isEmpty()) {
                for (int i = 0; i < audioInputComboBox.getItemCount(); i++) {
                    if (audioInputComboBox.getItemAt(i).equals(lastInput)) {
                        audioInputComboBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }

        // Output: prefer hardware identity
        String savedOutputId = prefs.get(PREF_AUDIO_OUTPUT_ID, "");
        boolean outputSelected = false;
        if (savedOutputId != null && !savedOutputId.isEmpty()) {
            try {
                Mixer.Info[] infos = AudioSystem.getMixerInfo();
                for (Mixer.Info mi : infos) {
                    try {
                        Mixer m = AudioSystem.getMixer(mi);
                        if (!m.isLineSupported(new Line.Info(SourceDataLine.class))) continue; // output only
                        String id = new AudioDeviceWatcher.Identity(mi.getName(), mi.getVendor(), mi.getDescription(), mi.getVersion()).toString();
                        if (savedOutputId.equals(id)) {
                            String name = mi.getName();
                            for (int i = 0; i < audioOutputComboBox.getItemCount(); i++) {
                                if (name.equals(audioOutputComboBox.getItemAt(i))) {
                                    audioOutputComboBox.setSelectedIndex(i);
                                    outputSelected = true;
                                    break;
                                }
                            }
                            break;
                        }
                    } catch (Throwable ignore) {}
                }
            } catch (Throwable ignore) {}
        }
        if (!outputSelected) {
            // Fallback: restore by last known display name
            String lastOutput = prefs.get(PREF_AUDIO_OUTPUT, "");
            if (!lastOutput.isEmpty()) {
                for (int i = 0; i < audioOutputComboBox.getItemCount(); i++) {
                    if (audioOutputComboBox.getItemAt(i).equals(lastOutput)) {
                        audioOutputComboBox.setSelectedIndex(i);
                        break;
                    }
                }
            }
        }

        String lastMode = prefs.get(PREF_DIGITAL_MODE, "CW");
        // Migrate legacy labels
        if ("Morse Code".equals(lastMode)) lastMode = "CW";
        if ("PSK31".equals(lastMode)) lastMode = "PSK";
        // Validate against current options
        if (!"CW".equals(lastMode) && !"RTTY".equals(lastMode) && !"PSK".equals(lastMode)) {
            lastMode = "CW";
        }
        digitalModeComboBox.setSelectedItem(lastMode);

        // Load RTTY tuning prefs
        int rttyShift = prefs.getInt(PREF_RTTY_SHIFT, 170);
        double rttyBaud = prefs.getDouble(PREF_RTTY_BAUD, 45.45);
        boolean rttyInvert = prefs.getBoolean(PREF_RTTY_INVERT, false);
        if (rttyShiftSpinner != null) rttyShiftSpinner.setValue(rttyShift);
        if (rttyBaudSpinner != null) rttyBaudSpinner.setValue(rttyBaud);
        if (rttyInvertCheckBox != null) rttyInvertCheckBox.setSelected(rttyInvert);

        // Load CW tuning prefs
        if (cwAutoWpmCheckBox != null) cwAutoWpmCheckBox.setSelected(prefs.getBoolean("cwAutoWpm", true));
        if (cwWpmSpinner != null) cwWpmSpinner.setValue(prefs.getInt("cwWpm", 20));
        if (cwSquelchSpinner != null) cwSquelchSpinner.setValue(prefs.getDouble("cwSquelch", 2.0));

        // Auto Connect
        boolean autoConnect = prefs.getBoolean(PREF_AUTO_CONNECT, false);
        autoConnectCheckBox.setSelected(autoConnect);

        if (autoConnect) {
            SwingUtilities.invokeLater(this::connect);
        }
    }

    private void saveSettings() {
        String selectedPortLabel = (String) serialPortComboBox.getSelectedItem();
        String selectedPortName = extractSystemName(selectedPortLabel);
        if (selectedPortName != null && !selectedPortName.isEmpty()) {
            prefs.put(PREF_SERIAL_PORT, selectedPortName);

            // Find the SerialPort object to get details
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort port : ports) {
                if (selectedPortName.equals(port.getSystemPortName())) {
                    String serialNumber = port.getSerialNumber();
                    String description = port.getPortDescription();

                    if (serialNumber != null) prefs.put(PREF_SERIAL_PORT_SERIAL_NUMBER, serialNumber);
                    else prefs.remove(PREF_SERIAL_PORT_SERIAL_NUMBER);

                    if (description != null) prefs.put(PREF_SERIAL_PORT_DESCRIPTION, description);
                    else prefs.remove(PREF_SERIAL_PORT_DESCRIPTION);

                    break;
                }
            }
        }

        // Save PTT Port preference
        String selectedPttLabel = pttPortComboBox != null ? (String) pttPortComboBox.getSelectedItem() : null;
        String selectedPttName = extractSystemName(selectedPttLabel);
        if (selectedPttName != null && !selectedPttName.isEmpty()) {
            prefs.put(PREF_PTT_PORT, selectedPttName);
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort port : ports) {
                if (selectedPttName.equals(port.getSystemPortName())) {
                    String serialNumber = port.getSerialNumber();
                    String description = port.getPortDescription();
                    if (serialNumber != null) prefs.put(PREF_PTT_PORT_SERIAL_NUMBER, serialNumber);
                    else prefs.remove(PREF_PTT_PORT_SERIAL_NUMBER);
                    if (description != null) prefs.put(PREF_PTT_PORT_DESCRIPTION, description);
                    else prefs.remove(PREF_PTT_PORT_DESCRIPTION);
                    break;
                }
            }
        }

        if (baudRateComboBox.getSelectedItem() != null)
            prefs.putInt(PREF_BAUD_RATE, (Integer) baudRateComboBox.getSelectedItem());

        if (dataBitsComboBox.getSelectedItem() != null)
            prefs.putInt(PREF_DATA_BITS, (Integer) dataBitsComboBox.getSelectedItem());

        if (stopBitsComboBox.getSelectedItem() != null)
            prefs.put(PREF_STOP_BITS, (String) stopBitsComboBox.getSelectedItem());

        if (parityComboBox.getSelectedItem() != null)
            prefs.put(PREF_PARITY, (String) parityComboBox.getSelectedItem());

        if (flowControlComboBox.getSelectedItem() != null)
            prefs.put(PREF_FLOW_CONTROL, (String) flowControlComboBox.getSelectedItem());

        if (serialTxControlComboBox != null && serialTxControlComboBox.getSelectedItem() != null)
            prefs.put(PREF_SERIAL_TX_CONTROL, (String) serialTxControlComboBox.getSelectedItem());

        if (audioInputComboBox.getSelectedItem() != null)
            prefs.put(PREF_AUDIO_INPUT, (String) audioInputComboBox.getSelectedItem());

        if (audioOutputComboBox.getSelectedItem() != null)
            prefs.put(PREF_AUDIO_OUTPUT, (String) audioOutputComboBox.getSelectedItem());

        if (digitalModeComboBox.getSelectedItem() != null)
            prefs.put(PREF_DIGITAL_MODE, (String) digitalModeComboBox.getSelectedItem());

        // Persist RTTY tuning values
        if (rttyShiftSpinner != null) {
            Object shiftObj = rttyShiftSpinner.getValue();
            int shift = (shiftObj instanceof Number) ? ((Number) shiftObj).intValue() : 170;
            prefs.putInt(PREF_RTTY_SHIFT, shift);
        }
        if (rttyBaudSpinner != null) {
            Object baudObj = rttyBaudSpinner.getValue();
            double baud = (baudObj instanceof Number) ? ((Number) baudObj).doubleValue() : 45.45;
            prefs.putDouble(PREF_RTTY_BAUD, baud);
        }
        if (rttyInvertCheckBox != null) {
            prefs.putBoolean(PREF_RTTY_INVERT, rttyInvertCheckBox.isSelected());
        }

        // Persist CW tuning values
        if (cwAutoWpmCheckBox != null) prefs.putBoolean("cwAutoWpm", cwAutoWpmCheckBox.isSelected());
        if (cwWpmSpinner != null && cwWpmSpinner.getValue() instanceof Number) prefs.putInt("cwWpm", ((Number) cwWpmSpinner.getValue()).intValue());
        if (cwSquelchSpinner != null && cwSquelchSpinner.getValue() instanceof Number) prefs.putDouble("cwSquelch", ((Number) cwSquelchSpinner.getValue()).doubleValue());

    }

    // Stub methods to satisfy Main.java calls
    public void setupDeviceSelectionCommands() {}
    public void setServerConnectedListener(Consumer<Object> listener) {}
    public void addInputDeviceSelectionListener(ActionListener listener) {
        if (audioInputComboBox != null && listener != null) {
            audioInputComboBox.addActionListener(listener);
        }
    }
    public void addOutputDeviceSelectionListener(ActionListener listener) {
        if (audioOutputComboBox != null && listener != null) {
            audioOutputComboBox.addActionListener(listener);
        }
    }
    public String getSelectedInputDevice() { return (String) audioInputComboBox.getSelectedItem(); }
    public String getSelectedOutputDevice() { return (String) audioOutputComboBox.getSelectedItem(); }
    public void setAudioVisualizer(Object visualizer) {}

    // Expose the Digital Mode combo box so Main can place it on the Waterfall toolbar
    public JComboBox<String> getDigitalModeComboBox() { return digitalModeComboBox; }

    public void setWaterfall(SpectrogramDisplay waterfall) {
        this.waterfall = waterfall;
        // Hook zoom view changes to processing when available
        if (this.waterfall != null) {
            this.waterfall.addViewChangeListener(range -> {
                try {
                    handleZoomRangeChange(range);
                } catch (Throwable ignore) {}
            });
        }
    }

    // React to zoom window changes from the Waterfall
    private void handleZoomRangeChange(double[] range) {
        if (range == null || range.length < 2) return;
        double start = range[0];
        double end = range[1];
        double width = Math.max(0, end - start);
        if (audioCapture != null) {
            try {
                // If zoom width is essentially the full available band, restore UI-selected bandwidth/resolution
                int sr = audioCapture.getSampleRate();
                int nyquist = sr / 2;
                // Allow some margin for rounding
                if (width >= nyquist - 100) {
                    // Full/reset view: re-apply UI settings so processing bandwidth/resolution resets
                    updateBandwidth();
                    updateResolution();
                } else {
                    // Zoomed view: pass window to capture (zoom-linked processing/auto-res policy applies)
                    audioCapture.setZoomWindow(start, end);
                }
            } catch (Throwable ignore) {}
            // Update effective sample rate in the ruler to match current decimation
            try {
                waterfall.setInputSampleRate(audioCapture.getEffectiveSampleRate());
                updateSampleRateLabel();
            } catch (Throwable ignore) {}
        }
    }

    private boolean isHamlibSelected() {
            try {
                String sel = (String) serialPortComboBox.getSelectedItem();
                return HAMLIB_OPTION_LABEL.equals(sel);
            } catch (Throwable ignore) { return false; }
        }

        private String mapLabelToHamlibMode(String label) {
            if (label == null) return "";
            switch (label) {
                case "LSB": return "LSB";
                case "USB": return "USB";
                case "CW-U": return "CW";
                case "CW-L": return "CWR";
                case "FM": return "FM";
                case "AM": return "AM";
                case "RTTY-L": return "RTTY";
                case "RTTY-U": return "RTTYR";
                case "DATA-U": return "PKTUSB";
                case "DATA-L": return "PKTLSB";
                case "DATA-FM": return "FM"; // fallback
                case "FM-N": return "FMN";
                case "DATA-FM-N": return "FMN";
                case "PSK": return "PKTUSB";
                default: return label.toUpperCase();
            }
        }

        private int mapHamlibModeToIndex(String hamMode) {
            if (hamMode == null) return -1;
            String m = hamMode.toUpperCase();
            // Map to our MODE_LABELS
            if ("LSB".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("LSB");
            if ("USB".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("USB");
            if ("CW".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("CW-U");
            if ("CWR".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("CW-L");
            if ("FM".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("FM");
            if ("FMN".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("FM-N");
            if ("AM".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("AM");
            if ("RTTY".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("RTTY-L");
            if ("RTTYR".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("RTTY-U");
            if ("PKTUSB".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("DATA-U");
            if ("PKTLSB".equals(m)) return java.util.Arrays.asList(MODE_LABELS).indexOf("DATA-L");
            // Fallback: try exact label match
            int idx = java.util.Arrays.asList(MODE_LABELS).indexOf(m);
            return idx;
        }

        // Route control actions to active backend (Hamlib if connected, else Serial)
        private void rcSetFrequency(long hz) {
            try {
                if (hamlibClient != null && hamlibClient.isConnected()) { hamlibClient.setFrequency(hz); return; }
                if (radioControl != null) radioControl.setFrequency(hz);
            } catch (Throwable ignore) {}
        }
        private void rcSetPTT(boolean on) {
            try {
                if (hamlibClient != null && hamlibClient.isConnected()) { hamlibClient.setPTT(on); return; }
                if (radioControl != null) radioControl.setPTT(on);
            } catch (Throwable ignore) {}
        }
        private void rcSetModeByLabel(String label) {
            try {
                if (hamlibClient != null && hamlibClient.isConnected()) {
                    String hamMode = mapLabelToHamlibMode(label);
                    hamlibClient.setMode(hamMode, 0);
                    return;
                }
                if (radioControl != null) {
                    int idx = java.util.Arrays.asList(MODE_LABELS).indexOf(label);
                    if (idx >= 0 && idx < MODE_CODES.length) radioControl.setMode(MODE_CODES[idx]);
                }
            } catch (Throwable ignore) {}
        }
        private void rcSetActiveVFO(char v) {
            try {
                if (hamlibClient != null && hamlibClient.isConnected()) { hamlibClient.setActiveVFO(v); return; }
                if (radioControl != null) radioControl.setActiveVFO(v);
            } catch (Throwable ignore) {}
        }

        private void connect() {
        String selectedInput = (String) audioInputComboBox.getSelectedItem();
        String selectedPort = extractSystemName(serialPortComboBox.getSelectedItem());
        String selectedPttPort = pttPortComboBox != null ? extractSystemName(pttPortComboBox.getSelectedItem()) : null;

        // Allow connection even if audio endpoints are currently absent.
        if (selectedInput == null || selectedInput.startsWith("No Audio")) {
            Main.setFooterText("No audio input present — will connect serial and wait for audio devices…");
        }

        saveSettings();

        // Initialize Audio Capture
        if (audioCapture == null) {
            audioCapture = new AudioCapture();
        }

        // Initialize Radio Control
        if (radioControl == null) {
            radioControl = new RadioControl();
        }

        // Set log listener (log only when console is visible to reduce EDT load)
        radioControl.setLogListener((dir, msg) -> {
            if (serialConsole != null && serialConsole.isShowing()) {
                serialConsole.log(dir, msg);
            }
        });

        // Toggle radio controls based on CAT connection status
        radioControl.setConnectionListener(connected -> SwingUtilities.invokeLater(() -> setRadioControlsEnabled(Boolean.TRUE.equals(connected))));

        try {
            // Prepare Audio RX/TX but do not hard-fail when devices are absent.
            lastFpsTime = System.currentTimeMillis();
            frameCount = 0;

            // Ensure instances
            if (audioCapture == null) audioCapture = new AudioCapture();
            if (audioPlayback == null) audioPlayback = new AudioPlayback();

            // Build strict desired identities from current selection and persist
            desiredInputId = resolveSelectedInputIdentity();
            desiredOutputId = resolveSelectedOutputIdentity();
            persistAudioIdentities(desiredInputId, desiredOutputId);

            // Spectrum listener for Waterfall
            Consumer<double[]> cpuListener = spectrumData -> {
                if (waterfall != null) {
                    waterfall.updateSpectrogram(spectrumData);
                    updateFps();
                }
            };

            // Start watcher and attach immediately if already present
            if (audioWatcher != null) {
                try { audioWatcher.stop(); } catch (Throwable ignore) {}
            }
            audioWatcher = new AudioDeviceWatcher(750);
            audioWatcher.setDesired(desiredInputId, desiredOutputId);
            audioWatcher.setOnInputAppear(mi -> SwingUtilities.invokeLater(() -> {
                try {
                    if (!audioRxActive) {
                        audioCapture.start(mi, cpuListener);
                        // Link zoom behavior
                        audioCapture.setLinkProcessingToZoom(true);
                        audioCapture.setAutoResolutionOnZoom(true);
                        audioRxActive = true;
                        try { RemoteControlManager.getInstance().setAudioFormat(audioCapture.getSampleRate(), 1); } catch (Throwable ignore) {}
                        Main.setFooterText("Audio RX attached: " + mi.getName());
                        // Configure waterfall upon attach
                        if (waterfall != null) {
                            waterfall.setInputSampleRate(audioCapture.getSampleRate());
                            updateSampleRateLabel();
                            updateBandwidth();
                            updateColorTheme();
                            updateResolution();
                            updateSpeed();
                            updateDigitalModeSettings();
                        }
                    }
                } catch (Exception ex) {
                    Main.setFooterText("Failed to start audio RX: " + ex.getMessage());
                }
            }));
            audioWatcher.setOnInputDisappear(() -> SwingUtilities.invokeLater(() -> {
                if (audioRxActive) {
                    try { audioCapture.stop(); } catch (Throwable ignore) {}
                    audioRxActive = false;
                    Main.setFooterText("Audio RX lost — waiting for device…");
                }
            }));
            audioWatcher.setOnOutputAppear(mi -> SwingUtilities.invokeLater(() -> {
                if (!audioTxActive) {
                    try {
                        audioPlayback.start(mi);
                        audioTxActive = true;
                        Main.setFooterText("Audio TX attached: " + mi.getName());
                        updateSampleRateLabel();
                    } catch (Exception ex) {
                        Main.setFooterText("Failed to start audio TX: " + ex.getMessage());
                    }
                }
            }));
            audioWatcher.setOnOutputDisappear(() -> SwingUtilities.invokeLater(() -> {
                if (audioTxActive) {
                    try { audioPlayback.stop(); } catch (Throwable ignore) {}
                    audioTxActive = false;
                    Main.setFooterText("Audio TX lost — waiting for device…");
                }
            }));
            audioWatcher.start();

            // Kick an immediate probe by invoking appear callbacks if present now
            // (Watcher will detect within the first interval anyway.)
            Main.setFooterText("Connected (Serial), Waiting for Audio …");

            // Initialize Encoder based on mode (Decoder will be created only when a selection is added)
            String selectedMode = (String) digitalModeComboBox.getSelectedItem();

            // Reset decoder/encoder
            decoder = null;
            encoder = null;

            if ("RTTY".equals(selectedMode) || selectedMode.startsWith("RTTY")) {
                encoder = new RTTYEncoder();
            } else if ("PSK31".equals(selectedMode)) {
                encoder = new PSK31Encoder();
            } else if (isJRadioMode(selectedMode)) {
                JRadio jRadio = new JRadio(selectedMode);
                // For receive, decoders will be tied to selections; keep encoder for TX
                encoder = jRadio;
            }

            // Note: Decoder(s) are created when the user adds selections on the waterfall.
            // This prevents any decoding when no digital signals are marked.

            // Update waterfall bandwidth based on audio capture sample rate
            if (waterfall != null) {
                waterfall.setInputSampleRate(audioCapture.getSampleRate());
                updateBandwidth();
                updateColorTheme();
                updateResolution();
                updateSpeed();
                updateDigitalModeSettings();
            }

            // Start Radio Control (if port selected)
            if (isHamlibSelected()) {
                            try {
                                if (hamlibClient == null) hamlibClient = new HamlibClient();
                                // Wire listeners to update UI
                                hamlibClient.setConnectionListener(ok -> SwingUtilities.invokeLater(() -> {
                                    if (ok) {
                                        setRadioControlsEnabled(true);
                                        Main.setFooterText("Connected (Hamlib), Waiting for Audio …");
                                    } else {
                                        setRadioControlsEnabled(false);
                                        Main.setFooterText("Hamlib disconnected");
                                    }
                                }));
                                hamlibClient.setFrequencyListener(freq -> {
                                    this.currentFrequency = freq;
                                    SwingUtilities.invokeLater(() -> {
                                        refreshFrequencyLabel();
                                        if (contactLogPanel != null) contactLogPanel.setContextFrequency(freq);
                                        if (waterfall != null) waterfall.setStartFrequency(freq);
                                    });
                                });
                                hamlibClient.setModeListener(mode -> SwingUtilities.invokeLater(() -> {
                                    if (modeComboBox != null) {
                                        isUpdatingUI = true;
                                        try {
                                            int idx = mapHamlibModeToIndex(mode);
                                            if (idx >= 0 && idx < MODE_LABELS.length && modeComboBox.getSelectedIndex() != idx) {
                                                modeComboBox.setSelectedIndex(idx);
                                                if (contactLogPanel != null) contactLogPanel.setContextMode(MODE_LABELS[idx]);
                                            }
                                        } finally { isUpdatingUI = false; }
                                    }
                                }));
                                hamlibClient.setTxListener(tx -> {
                                    // If you have a TX indicator or toggle, update here if needed
                                });
                                hamlibClient.setSMeterListener(val -> SwingUtilities.invokeLater(() -> {
                                    lastSMeterValue = val;
                                    if (sMeter != null) sMeter.setValue(val);
                                }));
                                // Sync VOX state from rig (Hamlib)
                                hamlibClient.setVOXListener(enabled -> SwingUtilities.invokeLater(() -> {
                                    if (voxButton != null) {
                                        updatingVox = true;
                                        try { voxButton.setSelected(Boolean.TRUE.equals(enabled)); } finally { updatingVox = false; }
                                    }
                                }));
                                // Sync AF/RF sliders from rig (Hamlib)
                                hamlibClient.setAFGainListener(val -> SwingUtilities.invokeLater(() -> {
                                    if (afGainSlider != null) {
                                        updatingRadioSliders = true;
                                        try { afGainSlider.setValue(val); } finally { updatingRadioSliders = false; }
                                    }
                                }));
                                hamlibClient.setRFGainListener(val -> SwingUtilities.invokeLater(() -> {
                                    if (rfGainSlider != null) {
                                        updatingRadioSliders = true;
                                        try { rfGainSlider.setValue(val); } finally { updatingRadioSliders = false; }
                                    }
                                }));
                                // Sync RF Power slider from rig (Hamlib)
                                hamlibClient.setRFPowerSettingListener(pct -> SwingUtilities.invokeLater(() -> {
                                    if (rfPowerSlider != null) {
                                        updatingRFPower = true;
                                        try {
                                            rfPowerSlider.setValue(pct);
                                            if (rfPowerValueLabel != null) rfPowerValueLabel.setText(pct + "%");
                                        } finally { updatingRFPower = false; }
                                    }
                                }));

                                hamlibClient.connect("127.0.0.1", 4532);
                            } catch (Exception e) {
                                JOptionPane.showMessageDialog(this, "Failed to connect to Hamlib rigctld: " + e.getMessage());
                                e.printStackTrace();
                            }
                        } else if (selectedPort != null) {
                try {
                    int baudRate = (Integer) baudRateComboBox.getSelectedItem();
                    int dataBits = (Integer) dataBitsComboBox.getSelectedItem();

                    int stopBits = SerialPort.ONE_STOP_BIT;
                    String stopBitsStr = (String) stopBitsComboBox.getSelectedItem();
                    if ("1.5".equals(stopBitsStr)) stopBits = SerialPort.ONE_POINT_FIVE_STOP_BITS;
                    else if ("2".equals(stopBitsStr)) stopBits = SerialPort.TWO_STOP_BITS;

                    int parity = SerialPort.NO_PARITY;
                    String parityStr = (String) parityComboBox.getSelectedItem();
                    if ("Odd".equals(parityStr)) parity = SerialPort.ODD_PARITY;
                    else if ("Even".equals(parityStr)) parity = SerialPort.EVEN_PARITY;
                    else if ("Mark".equals(parityStr)) parity = SerialPort.MARK_PARITY;
                    else if ("Space".equals(parityStr)) parity = SerialPort.SPACE_PARITY;

                    int flowControl = SerialPort.FLOW_CONTROL_DISABLED;
                    String flowControlStr = (String) flowControlComboBox.getSelectedItem();
                    if ("RTS/CTS".equals(flowControlStr)) flowControl = SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED;
                    else if ("XON/XOFF".equals(flowControlStr)) flowControl = SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;

                    radioControl.connect(selectedPort, baudRate, dataBits, stopBits, parity, flowControl, selectedPttPort);

                    // Apply Serial TX Control selection (OFF / RTS / DTR)
                    try {
                        RadioControl.SerialTxControl ctrl = RadioControl.SerialTxControl.OFF;
                        if (serialTxControlComboBox != null && serialTxControlComboBox.getSelectedItem() != null) {
                            String s = serialTxControlComboBox.getSelectedItem().toString();
                            if ("RTS".equalsIgnoreCase(s)) ctrl = RadioControl.SerialTxControl.RTS;
                            else if ("DTR".equalsIgnoreCase(s)) ctrl = RadioControl.SerialTxControl.DTR;
                            else ctrl = RadioControl.SerialTxControl.OFF;
                        }
                        radioControl.setSerialTxControl(ctrl);
                    } catch (Throwable ignore) {}

                    radioControl.setFrequencyListener(freq -> {
                        this.currentFrequency = freq;
                        SwingUtilities.invokeLater(() -> {
                            refreshFrequencyLabel();
                            if (contactLogPanel != null) {
                                contactLogPanel.setContextFrequency(freq);
                            }
                        });
                        if (waterfall != null) {
                            waterfall.setStartFrequency(freq);
                        }
                    });

                    radioControl.setPowerListener(val -> {
                        if (powerMeter != null) powerMeter.setValue(val);
                    });

                    radioControl.setALCListener(val -> {
                        if (alcMeter != null) alcMeter.setValue(val);
                    });

                    radioControl.setSWRListener(val -> {
                        if (swrMeter != null) swrMeter.setValue(val);
                    });

                    radioControl.setSMeterListener(val -> {
                        lastSMeterValue = val;
                        if (sMeter != null) sMeter.setValue(val);
                    });

                    radioControl.setIDListener(val -> {
                        if (idMeter != null) idMeter.setValue(val);
                    });

                    radioControl.setVDDListener(val -> {
                        if (vddMeter != null) vddMeter.setValue(val);
                    });

                    radioControl.setTuningStatusListener(tuning -> SwingUtilities.invokeLater(() -> {
                        if (tuneButton != null) {
                            tuneButton.setSelected(tuning);
                        }
                    }));

                    radioControl.setModeListener(modeCode -> SwingUtilities.invokeLater(() -> {
                        if (modeComboBox != null) {
                            isUpdatingUI = true; // Set flag
                            try {
                                // Find index for code
                                for (int i = 0; i < MODE_CODES.length; i++) {
                                    if (MODE_CODES[i].equals(modeCode)) {
                                        if (modeComboBox.getSelectedIndex() != i) {
                                            modeComboBox.setSelectedIndex(i);
                                        }
                                        // Update Contact Log mode with human-readable label
                                        if (contactLogPanel != null && i >= 0 && i < MODE_LABELS.length) {
                                            contactLogPanel.setContextMode(MODE_LABELS[i]);
                                        }
                                        break;
                                    }
                                }
                            } finally {
                                isUpdatingUI = false; // Clear flag
                            }
                        }
                    }));

                    // VFO listener to reflect A/B selection
                    radioControl.setVFOListener(vfo -> SwingUtilities.invokeLater(() -> {
                        isUpdatingUI = true;
                        try {
                            activeVfo = vfo;
                            if (vfoAButton != null && vfoBButton != null) {
                                if (vfo == 'B') {
                                    if (!vfoBButton.isSelected()) vfoBButton.setSelected(true);
                                } else {
                                    if (!vfoAButton.isSelected()) vfoAButton.setSelected(true);
                                }
                            }
                            refreshFrequencyLabel();
                        } finally {
                            isUpdatingUI = false;
                        }
                    }));

                    // Split listener to keep toggle and display in sync
                    radioControl.setSplitListener(split -> SwingUtilities.invokeLater(() -> {
                        isUpdatingUI = true;
                        try {
                            if (splitToggle != null && splitToggle.isSelected() != split) {
                                splitToggle.setSelected(split);
                            }
                            refreshFrequencyLabel();
                        } finally {
                            isUpdatingUI = false;
                        }
                    }));

                    // Sync AF/RF/Width sliders from radio on connect and whenever radio reports updates
                    radioControl.setAFGainListener(val -> SwingUtilities.invokeLater(() -> {
                        if (afGainSlider != null) {
                            updatingRadioSliders = true;
                            try { afGainSlider.setValue(val); } finally { updatingRadioSliders = false; }
                        }
                    }));
                    radioControl.setRFGainListener(val -> SwingUtilities.invokeLater(() -> {
                        if (rfGainSlider != null) {
                            updatingRadioSliders = true;
                            try { rfGainSlider.setValue(val); } finally { updatingRadioSliders = false; }
                        }
                    }));
                    radioControl.setWidthIndexListener(idx -> SwingUtilities.invokeLater(() -> {
                        if (widthSlider != null) {
                            updatingRadioSliders = true;
                            try { widthSlider.setValue(idx); } finally { updatingRadioSliders = false; }
                        }
                    }));
                    // Sync RF Power slider from radio (PCxxx;)
                    radioControl.setRFPowerSettingListener(pct -> SwingUtilities.invokeLater(() -> {
                        if (rfPowerSlider != null) {
                            updatingRFPower = true;
                            try {
                                rfPowerSlider.setValue(pct);
                                if (rfPowerValueLabel != null) rfPowerValueLabel.setText(pct + "%");
                            } finally { updatingRFPower = false; }
                        }
                    }));
                    // Sync VOX state from radio (VX0/1;)
                    radioControl.setVOXListener(enabled -> SwingUtilities.invokeLater(() -> {
                        if (voxButton != null) {
                            updatingVox = true;
                            try { voxButton.setSelected(Boolean.TRUE.equals(enabled)); } finally { updatingVox = false; }
                        }
                    }));

                } catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "Failed to connect to serial port: " + e.getMessage() + "\nAudio will still be active.");
                    e.printStackTrace();
                }
            }

            connectButton.setText("Disconnect");
            // Disable combos while connected
            serialPortComboBox.setEnabled(false);
            if (pttPortComboBox != null) pttPortComboBox.setEnabled(false);
            baudRateComboBox.setEnabled(false);
            dataBitsComboBox.setEnabled(false);
            stopBitsComboBox.setEnabled(false);
            parityComboBox.setEnabled(false);
            flowControlComboBox.setEnabled(false);
            audioInputComboBox.setEnabled(false);
            audioOutputComboBox.setEnabled(false);
            refreshButton.setEnabled(false);

            // Start coalesced meter refresh timer (approx 30 FPS)
            if (meterRefreshTimer != null) {
                try { meterRefreshTimer.stop(); } catch (Throwable ignore) {}
            }
            meterRefreshTimer = new javax.swing.Timer(33, e -> {
                if (radioControlPanel != null && radioControlPanel.isShowing()) {
                    if (powerMeter != null) { powerMeter.updateString(); powerMeter.repaint(); }
                    if (alcMeter != null) { alcMeter.updateString(); alcMeter.repaint(); }
                    if (swrMeter != null) { swrMeter.updateString(); swrMeter.repaint(); }
                    if (sMeter != null) { sMeter.updateString(); sMeter.repaint(); }
                    if (idMeter != null) { idMeter.updateString(); idMeter.repaint(); }
                    if (vddMeter != null) { vddMeter.updateString(); vddMeter.repaint(); }
                }
            });
            meterRefreshTimer.start();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Failed to connect: " + e.getMessage());
            e.printStackTrace();
            disconnect(); // Cleanup partial connection
        }
    }

    private void setRadioControlsEnabled(boolean enabled) {
        if (radioControlPanel == null) return;
        SwingUtilities.invokeLater(() -> toggleComponentsRecursive(radioControlPanel, enabled));
    }

    private void toggleComponentsRecursive(Component comp, boolean enabled) {
        try {
            // Do not disable the frequency readout label; it's informative
            if (comp == frequencyDisplay && !enabled) {
                // Keep label readable; ensure it's enabled for consistent theme rendering
                frequencyDisplay.setEnabled(true);
            } else {
                if (comp instanceof AbstractButton) {
                    ((AbstractButton) comp).setEnabled(enabled);
                } else if (comp instanceof JComboBox) {
                    ((JComboBox<?>) comp).setEnabled(enabled);
                } else if (comp instanceof JSlider) {
                    ((JSlider) comp).setEnabled(enabled);
                } else if (comp instanceof JSpinner) {
                    ((JSpinner) comp).setEnabled(enabled);
                } else if (comp instanceof JTextField) {
                    ((JTextField) comp).setEnabled(enabled);
                } else if (comp instanceof JLabel) {
                    // Labels remain enabled to show info
                    ((JLabel) comp).setEnabled(true);
                } else {
                    comp.setEnabled(enabled);
                }
            }
            if (comp instanceof Container) {
                for (Component child : ((Container) comp).getComponents()) {
                    toggleComponentsRecursive(child, enabled);
                }
            }
        } catch (Throwable ignore) {}
    }

    private AudioDeviceWatcher.Identity identityFromMixerInfo(Mixer.Info mi) {
        if (mi == null) return null;
        return new AudioDeviceWatcher.Identity(mi.getName(), mi.getVendor(), mi.getDescription(), mi.getVersion());
    }

    private AudioDeviceWatcher.Identity resolveSelectedInputIdentity() {
        try {
            String name = (String) audioInputComboBox.getSelectedItem();
            if (name == null) return null;
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            for (Mixer.Info mi : infos) {
                try {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (!m.isLineSupported(new Line.Info(TargetDataLine.class))) continue;
                    if (name.equals(mi.getName())) return identityFromMixerInfo(mi);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private AudioDeviceWatcher.Identity resolveSelectedOutputIdentity() {
        try {
            String name = (String) audioOutputComboBox.getSelectedItem();
            if (name == null) return null;
            Mixer.Info[] infos = AudioSystem.getMixerInfo();
            for (Mixer.Info mi : infos) {
                try {
                    Mixer m = AudioSystem.getMixer(mi);
                    if (!m.isLineSupported(new Line.Info(SourceDataLine.class))) continue;
                    if (name.equals(mi.getName())) return identityFromMixerInfo(mi);
                } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private void persistAudioIdentities(AudioDeviceWatcher.Identity in, AudioDeviceWatcher.Identity out) {
        if (prefs == null) return;
        try {
            if (in != null) prefs.put(PREF_AUDIO_INPUT_ID, in.toString()); else prefs.remove(PREF_AUDIO_INPUT_ID);
            if (out != null) prefs.put(PREF_AUDIO_OUTPUT_ID, out.toString()); else prefs.remove(PREF_AUDIO_OUTPUT_ID);
        } catch (Throwable ignore) {}
    }

    private void disconnect() {
        // Stop watcher first to avoid races while we tear down audio
        try {
            if (audioWatcher != null) {
                audioWatcher.stop();
                audioWatcher = null;
            }
        } catch (Throwable ignore) {}
        audioRxActive = false;
        audioTxActive = false;

        // Close PSK Reporter exporter if open
        if (pskExporter != null) {
            try { pskExporter.close(); } catch (Exception ignored) {}
            pskExporter = null;
        }
        if (audioCapture != null) {
            if (currentAudioListener != null) {
                audioCapture.removeAudioListener(currentAudioListener);
                currentAudioListener = null;
            }
            try { audioCapture.stop(); } catch (Throwable ignore) {}
        }
        if (audioPlayback != null) {
            try { audioPlayback.stop(); } catch (Throwable ignore) {}
        }
        if (radioControl != null) {
            radioControl.disconnect();
        }
        if (hamlibClient != null && hamlibClient.isConnected()) {
            try { hamlibClient.disconnect(); } catch (Throwable ignore) {}
        }

        // Stop meter refresh timer
        if (meterRefreshTimer != null) {
            try { meterRefreshTimer.stop(); } catch (Throwable ignore) {}
            meterRefreshTimer = null;
        }

        // Stop and clear any per-selection feeders to avoid background threads lingering
        if (!decoderFeeders.isEmpty()) {
            for (DecoderFeeder f : decoderFeeders.values()) {
                try { f.stop(); } catch (Throwable ignore) {}
            }
            decoderFeeders.clear();
        }
        // Stop default decoder feeder if present
        if (defaultDecoderFeeder != null) {
            try { defaultDecoderFeeder.stop(); } catch (Throwable ignore) {}
            defaultDecoderFeeder = null;
        }

        connectButton.setText("Connect");
        serialPortComboBox.setEnabled(true);
        if (pttPortComboBox != null) pttPortComboBox.setEnabled(true);
        baudRateComboBox.setEnabled(true);
        dataBitsComboBox.setEnabled(true);
        stopBitsComboBox.setEnabled(true);
        parityComboBox.setEnabled(true);
        flowControlComboBox.setEnabled(true);
        audioInputComboBox.setEnabled(true);
        audioOutputComboBox.setEnabled(true);
        refreshButton.setEnabled(true);
        // Disable radio controls when CAT is disconnected
        setRadioControlsEnabled(false);
        Main.setFooterText("Disconnected");
    }

    // Container for a snapshot of recent audio with its sample rate
    private static class AudioSlice {
        final float[] data;
        final int sampleRate;
        AudioSlice(float[] data, int sampleRate) {
            this.data = data;
            this.sampleRate = sampleRate;
        }
    }

    // Per-selection serialized feeder to ensure backfill is fully processed before live
    private static final class DecoderFeeder {
        private final SignalDecoder decoder;
        private final java.util.concurrent.ExecutorService exec;
        private boolean acceptingLive = false;
        private final java.util.List<Pending> pendingLive = new java.util.ArrayList<>();

        // Live bandpass configuration/state (per marker)
        private volatile boolean liveBpEnabled = false;
        private volatile double bpLowHz = 300.0;   // default safe
        private volatile double bpHighHz = 2700.0; // default safe
        private volatile int coeffSr = -1;
        private volatile double hpA = 0.0;      // one-pole HP coefficient (a)
        private volatile double lpAlpha = 0.0;  // one-pole LP alpha
        private double hpPrevY = 0.0, hpPrevX = 0.0, lpPrevY = 0.0; // filter state (runs on exec thread)

        private static final class Pending {
            final float[] data; final int sr;
            Pending(float[] d, int s) { this.data = d; this.sr = s; }
        }

        DecoderFeeder(SignalDecoder decoder, String name) {
            this.decoder = decoder;
            this.exec = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            });
        }

        // Configure or update the live bandpass edges in audio Hz
        public synchronized void setLiveBandpass(double lowHz, double highHz) {
            if (!(highHz > 0) || lowHz < 0) {
                liveBpEnabled = false;
                return;
            }
            if (highHz - lowHz < 5.0) {
                // too narrow to be meaningful; slightly widen
                double c = (lowHz + highHz) * 0.5;
                lowHz = Math.max(20.0, c - 2.5);
                highHz = c + 2.5;
            }
            this.bpLowHz = lowHz;
            this.bpHighHz = highHz;
            // Recompute coefficients lazily on next block (when we know sr)
            this.coeffSr = -1;
            this.liveBpEnabled = true;
        }

        public synchronized void disableLiveBandpass() {
            liveBpEnabled = false;
            coeffSr = -1;
            hpPrevY = hpPrevX = lpPrevY = 0.0;
        }

        private void ensureCoeffs(int sr) {
            if (!liveBpEnabled) return;
            if (sr <= 0) { liveBpEnabled = false; return; }
            if (coeffSr == sr && hpA > 0 && hpA < 1 && lpAlpha > 0 && lpAlpha < 1) return;
            double nyq = sr * 0.5 - 50.0;
            double fcLow = Math.max(20.0, Math.min(bpLowHz, Math.max(20.0, nyq - 5.0)));
            double fcHigh = Math.max(fcLow + 5.0, Math.min(bpHighHz, nyq));
            // HP: y[n] = a * (y[n-1] + x[n] - x[n-1]) with a = exp(-2π fc / fs)
            this.hpA = Math.exp(-2.0 * Math.PI * fcLow / Math.max(1.0, sr));
            // LP: y += alpha * (x - y) with alpha = 1 - exp(-2π fc / fs)
            this.lpAlpha = 1.0 - Math.exp(-2.0 * Math.PI * fcHigh / Math.max(1.0, sr));
            this.coeffSr = sr;
            // do not reset states to avoid transient pops when moving; minor mismatch is acceptable
        }

        private void applyLiveBandpassInPlace(float[] buf, int sr) {
            if (!liveBpEnabled || buf == null || buf.length == 0) return;
            ensureCoeffs(sr);
            if (!(hpA > 0 && hpA < 1 && lpAlpha > 0 && lpAlpha < 1)) return;
            double locHpPrevY = hpPrevY, locHpPrevX = hpPrevX, locLpPrevY = lpPrevY;
            for (int i = 0; i < buf.length; i++) {
                double x = buf[i];
                double hpY = hpA * (locHpPrevY + x - locHpPrevX);
                locHpPrevY = hpY;
                locHpPrevX = x;
                double lpY = locLpPrevY + lpAlpha * (hpY - locLpPrevY);
                locLpPrevY = lpY;
                buf[i] = (float) lpY;
            }
            // Publish updated states
            hpPrevY = locHpPrevY; hpPrevX = locHpPrevX; lpPrevY = locLpPrevY;
        }

        // Enqueue backfill and open the gate for live after it completes
        public synchronized void feedBackfill(float[] data, int sr) {
            if (data == null || data.length == 0 || decoder == null) return;
            final float[] copy = java.util.Arrays.copyOf(data, data.length);
            exec.submit(() -> {
                try { decoder.processSamples(copy, sr); } catch (Throwable ignore) {}
            });
            // Continuation to enable live and flush any queued lives on the same executor thread
            exec.submit(() -> {
                java.util.List<Pending> toFlush;
                synchronized (DecoderFeeder.this) {
                    acceptingLive = true;
                    toFlush = new java.util.ArrayList<>(pendingLive);
                    pendingLive.clear();
                }
                for (Pending p : toFlush) {
                    try {
                        // Apply bandpass if enabled before delivering queued lives
                        applyLiveBandpassInPlace(p.data, p.sr);
                        decoder.processSamples(p.data, p.sr);
                    } catch (Throwable ignore) {}
                }
            });
        }

        public synchronized void feedLive(float[] data, int sr) {
            if (data == null || data.length == 0 || decoder == null) return;
            final float[] copy = java.util.Arrays.copyOf(data, data.length);
            if (!acceptingLive) {
                // Queue until backfill finishes
                pendingLive.add(new Pending(copy, sr));
            } else {
                exec.submit(() -> {
                    try {
                        applyLiveBandpassInPlace(copy, sr);
                        decoder.processSamples(copy, sr);
                    } catch (Throwable ignore) {}
                });
            }
        }

        public void stop() {
            try { exec.shutdownNow(); } catch (Throwable ignore) {}
            synchronized (this) { pendingLive.clear(); acceptingLive = false; }
        }
    }

    // Thread-safe snapshot of the last msRequested of raw audio from the recent ring buffer
    private AudioSlice getRecentAudioSliceMillis(int msRequested) {
        synchronized (recentAudioLock) {
            if (recentAudioBuf == null || recentAudioBuf.length == 0 || recentAudioSampleRate <= 0) return null;
            int sr = recentAudioSampleRate;
            int need = Math.max(1, Math.min(recentAudioBuf.length, (int) Math.round(sr * (msRequested / 1000.0))));
            float[] out = new float[need];
            int cap = recentAudioBuf.length;
            int end = recentAudioPos; // write position for next sample
            int start = end - need;
            while (start < 0) start += cap;
            if (start + need <= cap) {
                System.arraycopy(recentAudioBuf, start, out, 0, need);
            } else {
                int first = cap - start;
                System.arraycopy(recentAudioBuf, start, out, 0, first);
                System.arraycopy(recentAudioBuf, 0, out, first, need - first);
            }
            return new AudioSlice(out, sr);
        }
    }

    // Feed a decoder with a short backfill from the recent audio buffer, then live stream continues via the main listener
    private void backfillDecodeIfAvailable(SignalDecoder d, int ms) {
        if (d == null || ms <= 0) return;
        AudioSlice slice = getRecentAudioSliceMillis(ms);
        if (slice == null || slice.data == null || slice.data.length == 0) return;
        Thread t = new Thread(() -> {
            try {
                d.processSamples(slice.data, slice.sampleRate);
            } catch (Throwable ignore) {}
        }, "BackfillDecode");
        t.setDaemon(true);
        t.start();
    }

    // Feed a decoder with a short backfill that is pre-sliced (band-passed) to the selection's bandwidth
    private void backfillDecodePreslicedIfAvailable(SpectrogramSelection sel, SignalDecoder d, int ms) {
        if (d == null || sel == null || ms <= 0) return;
        AudioSlice slice = getRecentAudioSliceMillis(ms);
        if (slice == null || slice.data == null || slice.data.length == 0) return;
        // Compute audio-space bandpass edges from selection
        double baseStart = 0.0;
        try { if (waterfall instanceof Waterfall wf) baseStart = wf.getStartFrequency(); } catch (Throwable ignore) {}
        double lowAbs = Math.max(0.0, sel.getStartFrequency());
        double highAbs = Math.max(lowAbs, sel.getStartFrequency() + sel.getBandwidth());
        double lowHz = Math.max(0.0, lowAbs - baseStart);
        double highHz = Math.max(lowHz, highAbs - baseStart);

        // Apply margin to be tolerant to drift and tuning
        final double widenFactor = 1.20; // +20%
        double center = (lowHz + highHz) * 0.5;
        double halfBw = (highHz - lowHz) * 0.5 * widenFactor;
        double fcLow = Math.max(20.0, center - Math.max(10.0, halfBw));
        double fcHigh = center + Math.max(10.0, halfBw);
        int sr = Math.max(1, slice.sampleRate);
        double nyq = sr * 0.5 - 50.0; // keep some headroom
        if (fcHigh > nyq) fcHigh = nyq;
        if (fcLow >= fcHigh - 5.0) fcLow = Math.max(20.0, fcHigh - 5.0);

        final double hpA = Math.exp(-2.0 * Math.PI * fcLow / Math.max(1.0, sr));
        final double lpAlpha = 1.0 - Math.exp(-2.0 * Math.PI * fcHigh / Math.max(1.0, sr));

        // If parameters look invalid (e.g., zero band), fall back to raw backfill
        if (!(lpAlpha > 0 && lpAlpha < 1) || !(hpA > 0 && hpA < 1)) {
            backfillDecodeIfAvailable(d, ms);
            return;
        }

        float[] filtered = new float[slice.data.length];
        double hpPrevY = 0.0, hpPrevX = 0.0, lpPrevY = 0.0;
        for (int i = 0; i < slice.data.length; i++) {
            double x = slice.data[i];
            double hpY = hpA * (hpPrevY + x - hpPrevX);
            hpPrevY = hpY;
            hpPrevX = x;
            double lpY = lpPrevY + lpAlpha * (hpY - lpPrevY);
            lpPrevY = lpY;
            filtered[i] = (float) lpY;
        }

        final float[] toFeed = filtered;
        final int srFeed = slice.sampleRate;
        // Prefer serialized feeder to ensure backfill fully precedes live
        String selId = sel.getId();
        DecoderFeeder feeder = (selId != null) ? decoderFeeders.get(selId) : null;
        if (feeder != null) {
            feeder.feedBackfill(toFeed, srFeed);
        } else {
            Thread t = new Thread(() -> {
                try { d.processSamples(toFeed, srFeed); } catch (Throwable ignore) {}
            }, "BackfillDecodeBP");
            t.setDaemon(true);
            t.start();
        }
    }

    private boolean isJRadioMode(String mode) {
        return mode.equals("AX.25") || /* Morse Code handled natively */ mode.equals("SSTV") ||
               mode.equals("Hellschreiber") || mode.equals("APRS") || mode.equals("POCSAG") ||
               mode.equals("LoRaWAN");
    }

    private void updateDigitalModeSettings() {
        
        // How much audio to backfill (from recent ring buffer) when a marker is (re)bound to a decoder
        // This enables near-instant decoding from the immediate past before live streaming continues.
        final int recentBackfillMillis = 8000; // 8 seconds
        String selectedMode = (String) digitalModeComboBox.getSelectedItem();
        if (selectedMode == null) selectedMode = "";
        // Update Contact Log panel with chosen digital mode label
        if (contactLogPanel != null) {
            contactLogPanel.setContextDigitalMode(selectedMode);
        }

        // Hide legacy shared tuning panels to avoid duplication; per-decoder controls are in the Decoders tab
        if (rttyTuningPanel != null) rttyTuningPanel.setVisible(false);
        if (pskTuningPanel != null) pskTuningPanel.setVisible(false);
        if (cwTuningPanel != null) cwTuningPanel.setVisible(false);

        if (waterfall == null) return;

        // Clear existing decoders; keep selections to persist markers across mode changes
        activeDecoders.clear();
        // Clear any per-selection scanners
        selectionScanners.clear();
        // Clear any existing decoder boxes in the UI; we'll recreate them for current selections
        clearDecoderBoxes();
        
        // Before re-binding, stop and clear any existing per-selection feeders
        if (!decoderFeeders.isEmpty()) {
            for (DecoderFeeder f : decoderFeeders.values()) {
                try { f.stop(); } catch (Throwable ignore) {}
            }
            decoderFeeders.clear();
        }
        // Stop default feeder if it was previously created for a default decoder
        if (defaultDecoderFeeder != null) {
            try { defaultDecoderFeeder.stop(); } catch (Throwable ignore) {}
            defaultDecoderFeeder = null;
        }
        // Re-bind existing selections, preserving each marker's own mode
        List<SpectrogramSelection> existingSelections = waterfall.getSelections();
        if (existingSelections != null && !existingSelections.isEmpty()) {
            for (SpectrogramSelection sel : existingSelections) {
                // Determine per-selection mode: keep existing if set; otherwise initialize from current global selection
                String perSelMode = (sel.getModeName() != null && !sel.getModeName().isEmpty()) ? sel.getModeName() : selectedMode;
                if (sel.getModeName() == null || sel.getModeName().isEmpty()) {
                    sel.setModeName(perSelMode);
                }
                SignalDecoder d = createDecoder(perSelMode);
                if (d != null) {
                    d.setTargetFrequency((int) Math.max(0, Math.min(((audioCapture != null) ? audioCapture.getSampleRate()/2.0 : 24000.0), (sel.getStartFrequency() + sel.getBandwidth()/2.0 - ((waterfall instanceof Waterfall wf) ? wf.getStartFrequency() : 0.0)))));
                    // Apply decoder-specific settings
                    applyRttySettingsIfApplicable(d);
                    applyPskSettingsIfApplicable(d);
                    applyCwSettingsIfApplicable(d);
                    // Tie decoder bandpass to marker width when applicable
                    if (d instanceof RTTYDecoder rdBp) {
                        try { rdBp.setBandpassWidthHz(Math.max(0.0, sel.getBandwidth())); } catch (Throwable ignore) {}
                    }
                    d.setDecodedTextListener(text -> {
                        // Feed into per-selection callsign scanner
                        CleanStreamScanner scanner = selectionScanners.get(sel.getId());
                        if (scanner == null) {
                            scanner = new CleanStreamScanner(call -> {
                                // Update marker label and repaint waterfall
                                SpectrogramSelection sref = sel;
                                SwingUtilities.invokeLater(() -> {
                                    sref.setLabel(call);
                                    if (waterfall instanceof JComponent comp) comp.repaint();
                                    if (chatBoxPanel != null) {
                                        String tag = String.format("<span style='color:%s; border: 1px solid %s; padding: 1px 3px; margin-right:4px;'>%s</span>", toHexString(sref.getColor()), toHexString(sref.getColor()), call);
                                        chatBoxPanel.appendHtmlTo(sref.getId(), tag + " detected", sref.getModeName(), sref.getColor());
                                    }
                                    if (contactLogPanel != null) {
                                        contactLogPanel.setDetectedCallsign(call);
                                    }
                                });
                            });
                            selectionScanners.put(sel.getId(), scanner);
                        }
                        scanner.addText(text);

                        if (chatBoxPanel != null) {
                            // Reverting to previous chat output method, avoiding span wrapper for every append
                            chatBoxPanel.appendTextTo(sel.getId(), text, sel.getModeName(), sel.getColor());
                        }
                        int centerHz = (int) (sel.getStartFrequency() + sel.getBandwidth() / 2);
                        sendPskReport(text, perSelMode, centerHz);
                    });
                    activeDecoders.put(sel.getId(), d);
                    // Create per-selection serialized feeder
                    DecoderFeeder feeder = new DecoderFeeder(d, "DecoderFeeder-" + sel.getId());
                    decoderFeeders.put(sel.getId(), feeder);
                    // Configure live bandpass around the selection for this feeder
                    try {
                        double baseStart = 0.0;
                        if (waterfall instanceof Waterfall wf) baseStart = wf.getStartFrequency();
                        double lowAbs = Math.max(0.0, sel.getStartFrequency());
                        double highAbs = Math.max(lowAbs, sel.getStartFrequency() + sel.getBandwidth());
                        double lowHz = Math.max(0.0, lowAbs - baseStart);
                        double highHz = Math.max(lowHz, highAbs - baseStart);
                        // Widen by 20% to tolerate drift, and enforce min 10 Hz half-band
                        final double widen = 1.20;
                        double c = (lowHz + highHz) * 0.5;
                        double hw = (highHz - lowHz) * 0.5 * widen;
                        double fcLow = Math.max(20.0, c - Math.max(10.0, hw));
                        double fcHigh = c + Math.max(10.0, hw);
                        int srLocal = (audioCapture != null) ? audioCapture.getSampleRate() : 48000;
                        double nyq = Math.max(100.0, srLocal * 0.5 - 50.0);
                        if (fcHigh > nyq) fcHigh = nyq;
                        if (fcLow >= fcHigh - 5.0) fcLow = Math.max(20.0, fcHigh - 5.0);
                        feeder.setLiveBandpass(fcLow, fcHigh);
                    } catch (Throwable ignore) {}
                    // Add UI control box for this selection
                    SwingUtilities.invokeLater(() -> addDecoderBox(sel));
                    // Immediately backfill recent audio to prime the decoder before live stream continues (pre-sliced to selection bandwidth)
                    backfillDecodePreslicedIfAvailable(sel, d, recentBackfillMillis);
                }
            }
        }

        double bandwidth = 0;
        if ("PSK31".equals(selectedMode) || "PSK".equals(selectedMode)) {
            bandwidth = 31.25;
        } else if (selectedMode.startsWith("RTTY") || "RTTY".equals(selectedMode)) {
            bandwidth = 250; // Approx
        } else if ("CW".equals(selectedMode)) {
            bandwidth = 100; // Narrow hover for CW
        } else {
            bandwidth = 3000; // Default wide
        }

        waterfall.setHoverBandwidth(bandwidth);
        waterfall.setHoverEnabled(true);

        // Handle new selections
        final String modeForSelections = selectedMode;
        waterfall.setSelectionAddedListener(sel -> {
            // Initialize the marker's mode label only once at creation time
            if (sel.getModeName() == null || sel.getModeName().isEmpty()) {
                sel.setModeName(modeForSelections);
            }
            SignalDecoder d = createDecoder(modeForSelections);
            if (d != null) {
                d.setTargetFrequency((int) Math.max(0, Math.min(((audioCapture != null) ? audioCapture.getSampleRate()/2.0 : 24000.0), (sel.getStartFrequency() + sel.getBandwidth()/2.0 - ((waterfall instanceof Waterfall wf) ? wf.getStartFrequency() : 0.0)))));
                // Apply decoder-specific settings
                applyRttySettingsIfApplicable(d);
                applyPskSettingsIfApplicable(d);
                applyCwSettingsIfApplicable(d);
                // Tie decoder bandpass to marker width when applicable
                if (d instanceof RTTYDecoder rdBp) {
                    try { rdBp.setBandpassWidthHz(Math.max(0.0, sel.getBandwidth())); } catch (Throwable ignore) {}
                }
                d.setDecodedTextListener(text -> {
                    // Feed into per-selection callsign scanner
                    CleanStreamScanner scanner = selectionScanners.get(sel.getId());
                    if (scanner == null) {
                        scanner = new CleanStreamScanner(call -> {
                            // Update marker label and repaint waterfall
                            SpectrogramSelection sref = sel;
                            SwingUtilities.invokeLater(() -> {
                                sref.setLabel(call);
                                if (waterfall instanceof JComponent comp) comp.repaint();
                                if (chatBoxPanel != null) {
                                    String tag = String.format("<span style='color:%s; border: 1px solid %s; padding: 1px 3px; margin-right:4px;'>%s</span>", toHexString(sref.getColor()), toHexString(sref.getColor()), call);
                                    chatBoxPanel.appendHtmlTo(sref.getId(), tag + " detected", sref.getModeName(), sref.getColor());
                                }
                                if (contactLogPanel != null) {
                                    contactLogPanel.setDetectedCallsign(call);
                                }
                            });
                        });
                        selectionScanners.put(sel.getId(), scanner);
                    }
                    scanner.addText(text);

                    if (chatBoxPanel != null) {
                        // Reverting to previous chat output method, avoiding span wrapper for every append
                        chatBoxPanel.appendTextTo(sel.getId(), text, sel.getModeName(), sel.getColor());
                    }
                    // PSK Reporter auto-upload
                    int centerHz = (int) (sel.getStartFrequency() + sel.getBandwidth() / 2);
                    sendPskReport(text, modeForSelections, centerHz);
                });
                // Log marker placement frequency for diagnostics
                try {
                    int centerHz = (int) Math.round(sel.getStartFrequency() + sel.getBandwidth() / 2.0);
                    System.out.println("[Marker] Placed at " + centerHz + " Hz");
                } catch (Throwable ignore) { }
                activeDecoders.put(sel.getId(), d);
                // Add UI control box for this selection
                SwingUtilities.invokeLater(() -> addDecoderBox(sel));
                // Immediately backfill recent audio to prime the decoder before live stream continues (pre-sliced to selection bandwidth)
                backfillDecodePreslicedIfAvailable(sel, d, recentBackfillMillis);
            }
            // Update chat markers and default active marker if none
            if (chatBoxPanel != null) {
                refreshChatMarkers();
                if (activeMarkerId == null || activeMarkerId.isEmpty()) {
                    activeMarkerId = sel.getId();
                }
            }
        });

        waterfall.setSelectionRemovedListener(sel -> {
            String id = sel.getId();
            activeDecoders.remove(id);
            // Stop and drop per-selection feeder, if any
            DecoderFeeder f = (id != null) ? decoderFeeders.remove(id) : null;
            if (f != null) {
                try { f.stop(); } catch (Throwable ignore) {}
            }
            // Drop per-selection scanner
            selectionScanners.remove(id);
            // Remove the corresponding UI box for this selection
            SwingUtilities.invokeLater(() -> removeDecoderBoxById(id));
            // Update chat markers; if active removed, clear or pick first available
            if (chatBoxPanel != null) {
                if (id != null && id.equals(activeMarkerId)) {
                    activeMarkerId = null;
                }
                refreshChatMarkers();
                // If cleared, pick first selection if available
                if (activeMarkerId == null && waterfall != null && waterfall.getSelections() != null && !waterfall.getSelections().isEmpty()) {
                    activeMarkerId = waterfall.getSelections().get(0).getId();
                }
            }
        });

        // Retune decoder when marker moves/resizes
        waterfall.setSelectionChangedListener(sel -> {
            if (sel == null || sel.getId() == null) return;
            SignalDecoder d = activeDecoders.get(sel.getId());
            if (d != null) {
                {
                    double centerAbs = sel.getStartFrequency() + sel.getBandwidth() / 2.0;
                    double baseStart = 0.0;
                    try {
                        if (waterfall instanceof Waterfall wf) baseStart = wf.getStartFrequency();
                    } catch (Throwable ignore) {}
                    double audioHz = centerAbs - baseStart;
                    int sr = (audioCapture != null) ? audioCapture.getSampleRate() : 48000;
                    if (audioHz < 0) audioHz = 0;
                    if (audioHz > sr / 2.0) audioHz = sr / 2.0;
                    d.setTargetFrequency((int) Math.round(audioHz));
                }
                // Update this selection's live bandpass so the live stream follows marker moves/resizes
                try {
                    DecoderFeeder feeder = decoderFeeders.get(sel.getId());
                    if (feeder != null) {
                        double baseStart = 0.0;
                        if (waterfall instanceof Waterfall wf2) baseStart = wf2.getStartFrequency();
                        double lowAbs = Math.max(0.0, sel.getStartFrequency());
                        double highAbs = Math.max(lowAbs, sel.getStartFrequency() + sel.getBandwidth());
                        double lowHz = Math.max(0.0, lowAbs - baseStart);
                        double highHz = Math.max(lowHz, highAbs - baseStart);
                        final double widen = 1.20;
                        double c = (lowHz + highHz) * 0.5;
                        double hw = (highHz - lowHz) * 0.5 * widen;
                        double fcLow = Math.max(20.0, c - Math.max(10.0, hw));
                        double fcHigh = c + Math.max(10.0, hw);
                        int srLocal = (audioCapture != null) ? audioCapture.getSampleRate() : 48000;
                        double nyq = Math.max(100.0, srLocal * 0.5 - 50.0);
                        if (fcHigh > nyq) fcHigh = nyq;
                        if (fcLow >= fcHigh - 5.0) fcLow = Math.max(20.0, fcHigh - 5.0);
                        feeder.setLiveBandpass(fcLow, fcHigh);
                    }
                } catch (Throwable ignore) {}
                // Update bandpass to follow the marker width for RTTY
                if (d instanceof RTTYDecoder rdBp) {
                    try { rdBp.setBandpassWidthHz(Math.max(0.0, sel.getBandwidth())); } catch (Throwable ignore) {}
                }
            }
        });

        // Re-initialize encoder for TX only; decoders are per-selection
        decoder = null;
        encoder = null;

        if ("RTTY".equals(selectedMode) || selectedMode.startsWith("RTTY")) {
            encoder = new RTTYEncoder();
        } else if ("PSK31".equals(selectedMode)) {
            encoder = new PSK31Encoder();
        } else if ("Morse Code".equals(selectedMode)) {
            encoder = new CWEncoder();
        } else if (isJRadioMode(selectedMode)) {
            JRadio jRadio = new JRadio(selectedMode);
            // For JRadio family, keep a default decoder (no waterfall marker required)
            decoder = jRadio;
            encoder = jRadio;
            // Create background feeder for the default decoder to avoid blocking the audio thread
            try { defaultDecoderFeeder = new DecoderFeeder(decoder, "DefaultDecoderFeeder"); } catch (Throwable ignore) { defaultDecoderFeeder = null; }
            if (chatBoxPanel != null) {
                final String modeName = selectedMode;
                decoder.setDecodedTextListener(text -> {
                    // Feed into default scanner (no selections in JRadio modes)
                    if (defaultScanner == null) {
                        defaultScanner = new CleanStreamScanner(call -> {
                            if (chatBoxPanel != null) {
                                String html = String.format("<span style='border:1px solid #999; padding:1px 3px; margin-right:4px;'><b>%s</b></span> detected", call);
                                chatBoxPanel.appendHtmlTo("MAIN", html, modeName, Color.WHITE);
                            }
                            if (contactLogPanel != null) contactLogPanel.setDetectedCallsign(call);
                        });
                    }
                    defaultScanner.addText(text);

                    chatBoxPanel.appendText(text);
                    sendPskReport(text, modeName, (int) currentFrequency);
                });
            }
        }

        // Update audio listener to feed default decoder (if any) and all active decoders
        if (currentAudioListener != null) {
            audioCapture.removeAudioListener(currentAudioListener);
        }

        currentAudioListener = samples -> {
            int srLocal = (audioCapture != null) ? audioCapture.getSampleRate() : 48000;
            // Feed default decoder via background feeder to avoid blocking audio thread
            if (decoder != null) {
                if (defaultDecoderFeeder != null) {
                    defaultDecoderFeeder.feedLive(samples, srLocal);
                } else {
                    // Fallback to direct call if feeder not yet initialized
                    decoder.processSamples(samples, srLocal);
                }
            }
            // Feed per-selection decoders via serialized feeders (ensures backfill precedes live)
            if (!activeDecoders.isEmpty()) {
                for (java.util.Map.Entry<String, SignalDecoder> e : activeDecoders.entrySet()) {
                    String id = e.getKey();
                    SignalDecoder d = e.getValue();
                    DecoderFeeder feeder = decoderFeeders.get(id);
                    if (feeder != null) {
                        feeder.feedLive(samples, srLocal);
                    } else {
                        // Fallback: direct call if feeder missing (e.g., legacy mode)
                        d.processSamples(samples, srLocal);
                    }
                }
            }
            try { RemoteControlManager.getInstance().pushAudio(samples); } catch (Throwable ignore) {}
            // Fill recent audio ring buffer for autodetection trials
            try {
                synchronized (recentAudioLock) {
                    if (recentAudioSampleRate != srLocal || recentAudioBuf == null) {
                        recentAudioSampleRate = srLocal;
                        int seconds = 30; // keep ~30 seconds of raw audio for backfill
                        int cap = Math.max(srLocal * seconds, 4096);
                        recentAudioBuf = new float[cap];
                        recentAudioPos = 0;
                    }
                    int cap = recentAudioBuf.length;
                    if (samples.length >= cap) {
                        // keep the most recent window
                        System.arraycopy(samples, samples.length - cap, recentAudioBuf, 0, cap);
                        recentAudioPos = 0;
                    } else {
                        int pos = recentAudioPos;
                        int rem = cap - pos;
                        if (samples.length <= rem) {
                            System.arraycopy(samples, 0, recentAudioBuf, pos, samples.length);
                            recentAudioPos = (pos + samples.length) % cap;
                        } else {
                            System.arraycopy(samples, 0, recentAudioBuf, pos, rem);
                            System.arraycopy(samples, rem, recentAudioBuf, 0, samples.length - rem);
                            recentAudioPos = (samples.length - rem);
                        }
                    }
                }
            } catch (Throwable ignore) { }
        };
        audioCapture.addAudioListener(currentAudioListener);

        // Re-initialize Audio Playback for new mode
        String selectedOutput = (String) audioOutputComboBox.getSelectedItem();
        if (selectedOutput != null && !selectedOutput.startsWith("No Audio")) {
            if (audioPlayback == null) {
                audioPlayback = new AudioPlayback();
            }
            try {
                audioPlayback.start(selectedOutput);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Update settings based on new mode
        updateBandwidth();
        updateColorTheme();
        updateResolution();
        updateSpeed();
        // Refresh decoder tab labels to reflect new mode selection
        updateDecoderBoxes();
        // Update Chat markers list for current selections
        refreshChatMarkers();
    }

    private SignalDecoder createDecoder(String mode) {
        if ("RTTY".equals(mode) || mode.startsWith("RTTY")) {
            return new RTTYDecoder();
        } else if ("PSK31".equals(mode) || "PSK".equals(mode)) {
            return new PSK31Decoder();
        } else if ("CW".equals(mode)) {
            return new CWDecoder();
        } else if (isJRadioMode(mode)) {
            return new JRadio(mode);
        }
        return null;
    }

    private String toHexString(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private void ensurePskExporter() {
        if (pskReporterPanel == null || !pskReporterPanel.isEnabled()) {
            if (pskExporter != null) {
                try { pskExporter.close(); } catch (Exception ignored) {}
                pskExporter = null;
            }
            return;
        }
        String host = pskReporterPanel.getHost();
        int port = pskReporterPanel.getPort();
        if (pskExporter != null && (host.equals(pskExporterHost) && port == pskExporterPort)) {
            return;
        }
        // Recreate exporter with new endpoint
        if (pskExporter != null) {
            try { pskExporter.close(); } catch (Exception ignored) {}
            pskExporter = null;
        }
        try {
            pskExporter = new IPFIXExporter(host, port);
            pskExporterHost = host;
            pskExporterPort = port;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String extractCallsign(String text) {
        if (text == null) return null;
        Matcher m = callsignPattern.matcher(text.toUpperCase());
        while (m.find()) {
            String call = m.group(1);
            // Basic sanity: must contain a digit
            if (call.matches(".*\\d.*")) {
                return call;
            }
        }
        return null;
    }

    private int estimateSnrDb() {
        // Placeholder: map 0..255 to approx -30..30 and clamp to -127..127
        int mapped = (lastSMeterValue - 128) / 2; // rough
        if (mapped < -127) mapped = -127;
        if (mapped > 127) mapped = 127;
        return mapped;
    }

    private void sendPskReport(String decodedText, String mode, int centerHz) {
        if (pskReporterPanel == null || !pskReporterPanel.isEnabled()) return;
        ensurePskExporter();
        if (pskExporter == null) return;

        String theirCall = extractCallsign(decodedText);
        if (theirCall == null || theirCall.isEmpty()) return; // need a callsign

        IPFIXExporter.PSKReport r = new IPFIXExporter.PSKReport();
        r.senderCallsign = theirCall; // they sent
        r.receiverCallsign = pskReporterPanel.getMyCallsign(); // we received
        r.senderLocator = ""; // unknown
        r.receiverLocator = pskReporterPanel.getMyLocator();
        long freq = centerHz;
        if (freq <= 0) freq = currentFrequency;
        r.frequencyHz = freq;
        r.snrDb = estimateSnrDb();
        r.imdDb = 0; // unknown
        r.decoderSoftware = "Transistor Studio";
        r.antennaInformation = pskReporterPanel.getAntennaInfo();
        r.mode = mode != null ? mode : "";
        int info = 1; // automatically extracted
        if (pskReporterPanel.isTest()) info |= 0x80;
        r.informationSource = info;
        r.persistentIdentifier = pskReporterPanel.getPersistentId();
        r.flowStartSeconds = Instant.now().getEpochSecond();
        r.rigInformation = pskReporterPanel.getRigInfo();
        r.messageBits = null; // not provided
        r.deltaTMillis = 0x8000; // unknown
        r.exportTimeSeconds = r.flowStartSeconds;

        pskExporter.sendRecord(r);
    }

    private void updateFps() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            double fps = frameCount * 1000.0 / (now - lastFpsTime);
            boolean capped = false;
            try {
                if (audioCapture != null) capped = audioCapture.isFpsCapActive();
            } catch (Throwable ignore) {}
            Main.setFooterFps(String.format("FPS: %.1f", fps), capped);
            frameCount = 0;
            lastFpsTime = now;
        }
    }

    // Sends the configured callsign in CW using audio playback, on a background thread
    // Tries VARA MYCALL first, then falls back to saved preference PREF_CALLSIGN.
    private void sendCallsignCWThreaded() {
        new Thread(() -> {
            try {
                String call = (varaMyCallField != null) ? varaMyCallField.getText().trim() : "";
                if (call.isEmpty() && prefs != null) call = prefs.get(PREF_CALLSIGN, "").trim();
                if (call.isEmpty()) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "Set your callsign in VARA 'MYCALL' or Preferences first.",
                            "Missing Callsign",
                            JOptionPane.WARNING_MESSAGE));
                    return;
                }

                // Boundary check: treat CW like DIGITAL for enforcement purposes
                if (prefs != null && prefs.getBoolean(PREF_BOUNDARY_ENABLE_DIGITAL, false)) {
                    License lic = getSelectedLicense();
                    if (lic != License.OFF) {
                        long freq = currentFrequency;
                        if (freq > 0 && !BandPlan.isTxAllowed(lic, freq, ModeCategory.CW, null)) {
                            final String msg = "CW TX blocked for license: " + lic + "\n" +
                                    "Current frequency: " + formatFrequency(freq) + "\n\n" +
                                    "Allowed CW/DIGITAL ranges:" + "\n" + BandPlan.allowedRangesSummary(lic, ModeCategory.DIGITAL);
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, msg, "Out-of-band (CW)", JOptionPane.WARNING_MESSAGE));
                            return;
                        }
                    }
                }

                // Ensure we have a valid audio output
                String selectedOutput = (audioOutputComboBox != null && audioOutputComboBox.getSelectedItem() != null)
                        ? audioOutputComboBox.getSelectedItem().toString()
                        : null;
                if (selectedOutput == null || selectedOutput.startsWith("No Audio")) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "Select a valid audio output device first.",
                            "Audio Output",
                            JOptionPane.WARNING_MESSAGE));
                    return;
                }
                if (audioPlayback == null) audioPlayback = new AudioPlayback();
                try {
                    audioPlayback.start(selectedOutput);
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                            "Audio output error: " + ex.getMessage(),
                            "Audio Output",
                            JOptionPane.ERROR_MESSAGE));
                    return;
                }

                // Configure CW encoder (separate from current 'encoder' which may be a different mode)
                CWEncoder cw = new CWEncoder();
                int tone = (prefs != null) ? prefs.getInt(PREF_CW_TONE, 700) : 700;
                // Prefer tone from CW ID marker in the waterfall ruler, if available
                try {
                    if (waterfall instanceof Waterfall wf && audioPlayback != null) {
                        if (wf.isCwIdMarkerEnabled() && wf.getCwIdMarkerFrequencyHz() > 0) {
                            long base = wf.getStartFrequency();
                            int candidate = (int) Math.round(wf.getCwIdMarkerFrequencyHz() - base);
                            // clamp to valid audible range and Nyquist
                            int nyq = Math.max(1, audioPlayback.getSampleRate() / 2 - 1);
                            if (candidate >= 100 && candidate <= nyq) {
                                tone = candidate;
                            }
                        }
                    }
                } catch (Throwable ignore) { }
                int wpm = (prefs != null) ? prefs.getInt(PREF_CW_WPM, 20) : 20;
                cw.setToneHz(tone);
                cw.setWpm(wpm);
                if (chatBoxPanel != null) {
                    chatBoxPanel.appendText(String.format("CW ID tone: %d Hz\n", tone));
                }

                // Prepare the radio: enable VOX to key on audio without changing current mode
                if (radioControl != null) {
                    try {
                        radioControl.setVOX(true);
                        try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    } catch (Exception ignored) {
                    }
                }

                float[] samples = cw.generateSamples(call, audioPlayback.getSampleRate());
                audioPlayback.play(samples);

                if (radioControl != null) {
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    try { radioControl.setVOX(false); } catch (Exception ignored) {}
                }

                if (chatBoxPanel != null) {
                    chatBoxPanel.appendText("TX(CW): " + call + "\n");
                }
            } catch (Exception ex) {
                if (serialConsole != null) serialConsole.log("ERR", "CW send failed: " + ex.getMessage());
            }
        }, "CW-SendCallsign").start();
    }

    private static class TrackingProgressBar extends JProgressBar {
        private int maxValue = Integer.MIN_VALUE;
        private int minValue = Integer.MAX_VALUE;
        private long sum = 0;
        private long count = 0;
        private boolean trackMax = true;
        private boolean trackMin = false;
        private boolean trackAvg = false;
        private Function<Integer, String> valueFormatter;
        private String prefix = "";
        // New: last-value marker support
        private int lastValue = Integer.MIN_VALUE;
        private boolean showLastAsBlue = false;
        // Hold the bar filled for a short time after last update, then zero it
        private long lastUpdateMillis = 0L;
        private long activeHoldMillis = 1000L; // default 1 second
        // Dark blue for last-value marker
        private Color lastMarkerColor = new Color(13, 71, 161);

        public TrackingProgressBar(int min, int max) {
            super(min, max);
            setStringPainted(true);
        }

        public void setValueFormatter(Function<Integer, String> formatter) {
            this.valueFormatter = formatter;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public void setTrackMin(boolean trackMin) {
            this.trackMin = trackMin;
        }

        public void setTrackAvg(boolean trackAvg) {
            this.trackAvg = trackAvg;
        }

        public void setShowLastAsBlue(boolean show) {
            this.showLastAsBlue = show;
        }

        /**
         * How long (ms) to keep the bar filled with the last sample before auto-clearing to zero when no updates arrive.
         */
        public void setActiveHoldMillis(long ms) {
            if (ms < 0) ms = 0;
            this.activeHoldMillis = ms;
        }

        /**
         * Set the color of the last-value marker line.
         */
        public void setLastMarkerColor(Color c) {
            if (c != null) this.lastMarkerColor = c;
        }

        @Override
        public void setValue(int n) {
            // Record last value and stats
            if (showLastAsBlue) {
                lastValue = n;
                lastUpdateMillis = System.currentTimeMillis();
                // Show the bar filled immediately while active window is in effect
                super.setValue(n);
            } else {
                super.setValue(n);
            }
            if (trackMax && n > maxValue) {
                maxValue = n;
            }
            // Track min only if active (n > 0)
            if (trackMin && n > 0 && n < minValue) {
                minValue = n;
            }
            if (trackAvg) {
                sum += n;
                count++;
            }
            // No updateString() or repaint() here; caller/timer should refresh as needed
        }

        public void reset() {
            // Reset stats and last marker
            int base = showLastAsBlue ? getMinimum() : getValue();
            maxValue = base;
            // Reset min to current value if > 0, else wait for next active value
            minValue = (base > 0) ? base : Integer.MAX_VALUE;
            sum = base;
            count = 1;
            lastValue = Integer.MIN_VALUE;
            // No immediate repaint; timer will refresh
        }

        public void updateString() {
            String currentStr;
            String maxStr;
            String minStr = "";
            String avgStr = "";

            // Auto-clear the visual fill if we have been inactive past the hold time.
            if (showLastAsBlue) {
                long now = System.currentTimeMillis();
                if (lastUpdateMillis == 0L || (now - lastUpdateMillis) > activeHoldMillis) {
                    // Ensure the visual bar is zero but keep the last-value marker intact
                    if (getValue() != getMinimum()) {
                        super.setValue(getMinimum());
                    }
                }
            }

            int avg = (count > 0) ? (int)(sum / count) : 0;

            int displayedCurrent = showLastAsBlue && lastValue != Integer.MIN_VALUE ? lastValue : getValue();

            if (valueFormatter != null) {
                currentStr = valueFormatter.apply(displayedCurrent);
                maxStr = (maxValue != Integer.MIN_VALUE) ? valueFormatter.apply(maxValue) : "---";
                if (trackMin && minValue != Integer.MAX_VALUE) {
                    minStr = " Min: " + valueFormatter.apply(minValue);
                }
                if (trackAvg && count > 0) {
                    avgStr = " Avg: " + valueFormatter.apply(avg);
                }
            } else {
                currentStr = String.valueOf(displayedCurrent);
                maxStr = (maxValue != Integer.MIN_VALUE) ? String.valueOf(maxValue) : "---";
                if (trackMin && minValue != Integer.MAX_VALUE) {
                    minStr = " Min: " + minValue;
                }
                if (trackAvg && count > 0) {
                    avgStr = " Avg: " + avg;
                }
            }

            setString(prefix + " " + currentStr + " (Max: " + maxStr + minStr + avgStr + ")");
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            int h = getHeight();
            int range = getMaximum() - getMinimum();
            if (range <= 0) return;

            int xMax = -1;
            if (trackMax && maxValue != Integer.MIN_VALUE) {
                double percent = (double)(maxValue - getMinimum()) / range;
                if (percent < 0) percent = 0;
                if (percent > 1) percent = 1;
                xMax = (int)(w * percent);
                if (xMax >= w) xMax = w - 2;
            }

            int xMin = -1;
            if (trackMin && minValue != Integer.MAX_VALUE) {
                double percent = (double)(minValue - getMinimum()) / range;
                if (percent < 0) percent = 0;
                if (percent > 1) percent = 1;
                xMin = (int)(w * percent);
                if (xMin >= w) xMin = w - 2;
            }

            int xAvg = -1;
            if (trackAvg && count > 0) {
                int avg = (int)(sum / count);
                double percent = (double)(avg - getMinimum()) / range;
                if (percent < 0) percent = 0;
                if (percent > 1) percent = 1;
                xAvg = (int)(w * percent);
                if (xAvg >= w) xAvg = w - 2;
            }

            int xLast = -1;
            if (showLastAsBlue && lastValue != Integer.MIN_VALUE) {
                double percent = (double)(lastValue - getMinimum()) / range;
                if (percent < 0) percent = 0;
                if (percent > 1) percent = 1;
                xLast = (int)(w * percent);
                if (xLast >= w) xLast = w - 2;
            }

            java.util.Map<Integer, java.util.Set<String>> positions = new java.util.HashMap<>();
            if (xMax != -1) positions.computeIfAbsent(xMax, k -> new java.util.HashSet<>()).add("MAX");
            if (xMin != -1) positions.computeIfAbsent(xMin, k -> new java.util.HashSet<>()).add("MIN");
            if (xAvg != -1) positions.computeIfAbsent(xAvg, k -> new java.util.HashSet<>()).add("AVG");
            if (xLast != -1) positions.computeIfAbsent(xLast, k -> new java.util.HashSet<>()).add("LAST");

            for (java.util.Map.Entry<Integer, java.util.Set<String>> entry : positions.entrySet()) {
                int x = entry.getKey();
                java.util.Set<String> types = entry.getValue();

                Color c;
                boolean hasMax = types.contains("MAX");
                boolean hasMin = types.contains("MIN");
                boolean hasAvg = types.contains("AVG");
                boolean hasLast = types.contains("LAST");

                if ((hasMax && hasMin && hasAvg) || (hasLast && (hasMax || hasMin || hasAvg))) c = Color.WHITE;
                else if (hasMax && hasMin) c = Color.MAGENTA;
                else if (hasMax && hasAvg) c = Color.ORANGE;
                else if (hasMin && hasAvg) c = Color.GREEN;
                else if (hasMax) c = Color.RED;
                else if (hasMin) c = Color.BLUE;
                else if (hasLast) c = lastMarkerColor;
                else if (hasAvg) c = Color.YELLOW;
                else c = Color.BLACK;

                g.setColor(c);
                g.fillRect(x, 0, 2, h);
            }
        }
    }

    private JPanel createVARAHFPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("VXP"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        // Host
        gbc.gridx = 0; panel.add(new JLabel("Host:"), gbc);
        varaHostField = new JTextField("127.0.0.1");
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaHostField, gbc);

        // Control Port
        gbc.gridy++; gbc.gridx = 0; gbc.weightx = 0; panel.add(new JLabel("Control Port:"), gbc);
        varaCtrlPortField = new JTextField("8300");
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaCtrlPortField, gbc);

        // Data Port
        gbc.gridy++; gbc.gridx = 0; gbc.weightx = 0; panel.add(new JLabel("Data Port:"), gbc);
        varaDataPortField = new JTextField("8301");
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaDataPortField, gbc);

        // MYCALL
        gbc.gridy++; gbc.gridx = 0; gbc.weightx = 0; panel.add(new JLabel("MYCALL:"), gbc);
        varaMyCallField = new JTextField("KD0NDG");
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaMyCallField, gbc);

        // Target
        gbc.gridy++; gbc.gridx = 0; gbc.weightx = 0; panel.add(new JLabel("Target Call:"), gbc);
        varaTargetField = new JTextField(10);
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaTargetField, gbc);

        // Listen + Auto-reconnect
        gbc.gridy++; gbc.gridx = 0; gbc.weightx = 0;
        varaListenCb = new JCheckBox("Listen (Monitor)", true);
        panel.add(varaListenCb, gbc);
        gbc.gridx = 1;
        varaAutoReconnectCb = new JCheckBox("Auto-reconnect", false);
        panel.add(varaAutoReconnectCb, gbc);

        // Buttons row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        varaConnectBtn = new JButton("Connect Modem");
        varaDisconnectBtn = new JButton("Disconnect Modem");
        varaCallBtn = new JButton("CONNECT ARQ");
        JButton varaHangupBtn = new JButton("HANGUP");
        btnRow.add(varaConnectBtn);
        btnRow.add(varaDisconnectBtn);
        btnRow.add(varaCallBtn);
        btnRow.add(varaHangupBtn);
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        panel.add(btnRow, gbc);

        // File transfer row
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        varaSendFileBtn = new JButton("Send File…");
        varaSendFileBtn.setEnabled(false);
        varaFileProgress = new JProgressBar(0, 100);
        varaFileProgress.setPreferredSize(new Dimension(220, 20));
        varaFileStatus = new JLabel("");
        fileRow.add(varaSendFileBtn);
        fileRow.add(varaFileProgress);
        fileRow.add(varaFileStatus);
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        panel.add(fileRow, gbc);

        // Status
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 0;
        panel.add(new JLabel("Status:"), gbc);
        varaStatusLbl = new JLabel("Idle");
        gbc.gridx = 1; gbc.weightx = 1.0; panel.add(varaStatusLbl, gbc);

        // Wiring actions
        varaConnectBtn.addActionListener(e -> connectVARAModem());
        varaDisconnectBtn.addActionListener(e -> disconnectVARAModem());
        varaCallBtn.addActionListener(e -> {
            if (varaClient != null) {
                String tgt = varaTargetField.getText().trim();
                if (!tgt.isEmpty()) {
                    varaClient.connectARQ(tgt);
                }
            }
        });
        varaSendFileBtn.addActionListener(e -> {
            if (varaClient == null || !varaLinkUp) {
                JOptionPane.showMessageDialog(this, "VARA link is not up.", "Not connected", JOptionPane.WARNING_MESSAGE);
                return;
            }
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                java.io.File f = fc.getSelectedFile();
                long max = 50 * 1024; // 50 KB limit per user request
                if (f.length() > max) {
                    JOptionPane.showMessageDialog(this, "File exceeds 50 KB limit.", "Too large", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                varaSendFileBtn.setEnabled(false);
                varaFileProgress.setValue(0);
                varaFileStatus.setText("Preparing to send " + f.getName());
                varaClient.sendFile(f, pct -> SwingUtilities.invokeLater(() -> varaFileProgress.setValue(pct)),
                        status -> SwingUtilities.invokeLater(() -> varaFileStatus.setText(status)),
                        () -> SwingUtilities.invokeLater(() -> {
                            varaFileStatus.setText("Send complete: " + f.getName());
                            varaSendFileBtn.setEnabled(true);
                        }),
                        err -> SwingUtilities.invokeLater(() -> {
                            varaFileStatus.setText("Error: " + err);
                            varaSendFileBtn.setEnabled(true);
                        }));
            }
        });
        varaHangupBtn.addActionListener(e -> {
            if (varaClient != null) {
                varaClient.disconnectARQ();
            }
        });
        varaListenCb.addActionListener(e -> {
            if (varaClient != null) {
                varaClient.setListen(varaListenCb.isSelected());
            }
        });

        // --- VXP Networking (KISS) + Mesh Visualization ---
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JPanel vxpSection = new JPanel(new GridBagLayout());
        vxpSection.setBorder(BorderFactory.createTitledBorder("VXP Networking"));
        GridBagConstraints vgc = new GridBagConstraints();
        vgc.insets = new Insets(4,4,4,4);
        vgc.fill = GridBagConstraints.HORIZONTAL;
        vgc.gridy = 0; vgc.gridx = 0; vgc.gridwidth = 1; vgc.weightx = 0;
        
        // Row 0: Host/Port and Start/Stop
        vxpSection.add(new JLabel("Host:"), vgc);
        vxpHostField = new JTextField(prefs.get("vxp.host", "127.0.0.1"));
        vgc.gridx = 1; vgc.weightx = 0.6; vxpSection.add(vxpHostField, vgc);
        vgc.gridx = 2; vgc.weightx = 0; vxpSection.add(new JLabel("Port:"), vgc);
        vxpPortField = new JTextField(prefs.get("vxp.port", "8100"));
        vgc.gridx = 3; vgc.weightx = 0.2; vxpSection.add(vxpPortField, vgc);
        vxpStartBtn = new JButton("Start");
        vgc.gridx = 4; vgc.weightx = 0; vxpSection.add(vxpStartBtn, vgc);
        vxpStopBtn = new JButton("Stop");
        vxpStopBtn.setEnabled(false);
        vgc.gridx = 5; vgc.weightx = 0; vxpSection.add(vxpStopBtn, vgc);
        vxpStatusLbl = new JLabel("Disconnected");
        vgc.gridx = 6; vgc.weightx = 0.2; vgc.anchor = GridBagConstraints.WEST; vxpSection.add(vxpStatusLbl, vgc);
        
        // Row 1: MYCALL and DEST
        vgc.gridy++; vgc.gridx = 0; vgc.weightx = 0; vgc.anchor = GridBagConstraints.CENTER;
        vxpSection.add(new JLabel("MYCALL:"), vgc);
        vxpMyCallField = new JTextField(prefs.get("vxp.mycall", "N0CALL"));
        vgc.gridx = 1; vgc.weightx = 0.4; vxpSection.add(vxpMyCallField, vgc);
        vgc.gridx = 2; vgc.weightx = 0; vxpSection.add(new JLabel("To:"), vgc);
        vxpDestField = new JTextField(prefs.get("vxp.dest", "SERVER"));
        vgc.gridx = 3; vgc.weightx = 0.4; vxpSection.add(vxpDestField, vgc);
        vxpSendMsgBtn = new JButton("Send Message");
        vxpSendMsgBtn.setEnabled(false);
        vgc.gridx = 4; vgc.weightx = 0; vxpSection.add(vxpSendMsgBtn, vgc);
        
        // Row 2: Message area
        vgc.gridy++; vgc.gridx = 0; vgc.gridwidth = 6; vgc.weightx = 1.0; vgc.fill = GridBagConstraints.BOTH;
        vxpMessageArea = new JTextArea(3, 20);
        vxpMessageArea.setLineWrap(true);
        vxpMessageArea.setWrapStyleWord(true);
        JScrollPane msgScroll = new JScrollPane(vxpMessageArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        vxpSection.add(msgScroll, vgc);
        
        // Row 3: RX log
        vgc.gridy++; vgc.gridx = 0; vgc.gridwidth = 6; vgc.weightx = 1.0; vgc.fill = GridBagConstraints.BOTH;
        vxpRxArea = new JTextArea(4, 20);
        vxpRxArea.setEditable(false);
        JScrollPane rxScroll = new JScrollPane(vxpRxArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rxScroll.setBorder(BorderFactory.createTitledBorder("RX Log"));
        vxpSection.add(rxScroll, vgc);
        
        // Row 4: File chooser + progress for VXP
        vgc.gridy++; vgc.gridx = 0; vgc.gridwidth = 6; vgc.fill = GridBagConstraints.HORIZONTAL; vgc.weightx = 1.0;
        JPanel vxpFileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        vxpSendFileBtn = new JButton("Send File via VXP…");
        vxpSendFileBtn.setEnabled(false);
        vxpFileProgress = new JProgressBar(0, 100);
        vxpFileProgress.setPreferredSize(new Dimension(220, 20));
        vxpFileStatus = new JLabel("");
        vxpFileRow.add(vxpSendFileBtn);
        vxpFileRow.add(vxpFileProgress);
        vxpFileRow.add(vxpFileStatus);
        vxpSection.add(vxpFileRow, vgc);
        
        // Row 5: Mesh visualization
        vgc.gridy++; vgc.gridx = 0; vgc.gridwidth = 6; vgc.weightx = 1.0; vgc.weighty = 1.0; vgc.fill = GridBagConstraints.BOTH;
        vxpTracker = new NodeTracker();
        vxpMesh = new MeshFlowPanel(vxpTracker);
        vxpMesh.setPreferredSize(new Dimension(360, 200));
        vxpSection.add(vxpMesh, vgc);
        
        // Wire VXP actions
        vxpStartBtn.addActionListener(e -> connectVXP());
        vxpStopBtn.addActionListener(e -> disconnectVXP());
        vxpSendMsgBtn.addActionListener(e -> sendVxpMessage());
        vxpSendFileBtn.addActionListener(e -> sendVxpFile());
        
        panel.add(vxpSection, gbc);

        return panel;
    }

    private void connectVARAModem() {
        try {
            VARAClient.Config cfg = new VARAClient.Config();
            cfg.host = varaHostField.getText().trim().isEmpty() ? "127.0.0.1" : varaHostField.getText().trim();
            try { cfg.controlPort = Integer.parseInt(varaCtrlPortField.getText().trim()); } catch (Exception ignored) {}
            try { cfg.dataPort = Integer.parseInt(varaDataPortField.getText().trim()); } catch (Exception ignored) {}
            cfg.myCall = varaMyCallField.getText().trim().isEmpty() ? cfg.myCall : varaMyCallField.getText().trim();

            varaClient = new VARAClient(cfg);
            varaClient.setLogListener((dir, msg) -> {
                if (serialConsole != null) serialConsole.log(dir, msg);
            });
            varaClient.setStatusListener(st -> {
                varaStatusLbl.setText(st);
                boolean wasUp = varaLinkUp;
                if (st.toUpperCase().startsWith("CONNECTED ")) {
                    varaLinkUp = true;
                } else if (st.toUpperCase().startsWith("DISCONNECTED")) {
                    varaLinkUp = false;
                }
                boolean enableSend = varaLinkUp;
                if (varaSendFileBtn != null) varaSendFileBtn.setEnabled(enableSend);
            });
            varaClient.setChatListener(line -> {
                if (chatBoxPanel != null) chatBoxPanel.appendText("RX(VARAHF): " + line + "\n");
            });

            varaClient.connectModem();
            varaConnected = true;
            // Apply current listen setting
            varaClient.setListen(varaListenCb.isSelected());
            if (serialConsole != null) serialConsole.log("INFO", "VARA modem connected");
        } catch (Exception ex) {
            varaConnected = false;
            varaStatusLbl.setText("Error: " + ex.getMessage());
            if (serialConsole != null) serialConsole.log("ERR", ex.toString());
        }
    }

    private void disconnectVARAModem() {
        try {
            if (varaClient != null) {
                varaClient.disconnectModem();
            }
        } finally {
            varaConnected = false;
            varaLinkUp = false;
            if (serialConsole != null) serialConsole.log("INFO", "VARA modem disconnected");
            varaStatusLbl.setText("Idle");
        }
    }


    // ===== VXP (KISS) helpers =====
    private void connectVXP() {
        String host = vxpHostField != null ? vxpHostField.getText().trim() : "127.0.0.1";
        int port = 8100;
        try { if (vxpPortField != null) port = Integer.parseInt(vxpPortField.getText().trim()); } catch (Exception ignore) {}
        if (vxpClient != null && vxpClient.isConnected()) return;
        if (vxpClient == null) vxpClient = new VaraKissClient();
        // Listeners
        vxpClient.setConnectionListener(connected -> SwingUtilities.invokeLater(() -> {
            if (Boolean.TRUE.equals(connected)) {
                if (vxpStatusLbl != null) vxpStatusLbl.setText("Connected");
                if (vxpStartBtn != null) vxpStartBtn.setEnabled(false);
                if (vxpStopBtn != null) vxpStopBtn.setEnabled(true);
                if (vxpSendMsgBtn != null) vxpSendMsgBtn.setEnabled(true);
                if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(true);
            } else {
                if (vxpStatusLbl != null) vxpStatusLbl.setText("Disconnected");
                if (vxpStartBtn != null) vxpStartBtn.setEnabled(true);
                if (vxpStopBtn != null) vxpStopBtn.setEnabled(false);
                if (vxpSendMsgBtn != null) vxpSendMsgBtn.setEnabled(false);
                if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(false);
            }
        }));
        vxpClient.setPayloadListener(this::handleVxpPayload);
        try {
            if (vxpStatusLbl != null) vxpStatusLbl.setText("Connecting...");
            // Persist
            try {
                prefs.put("vxp.host", host);
                prefs.put("vxp.port", Integer.toString(port));
            } catch (Throwable ignore) {}
            vxpClient.connect(host, port);
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                if (vxpStatusLbl != null) vxpStatusLbl.setText("Error: " + ex.getMessage());
                if (vxpStartBtn != null) vxpStartBtn.setEnabled(true);
                if (vxpStopBtn != null) vxpStopBtn.setEnabled(false);
                if (vxpSendMsgBtn != null) vxpSendMsgBtn.setEnabled(false);
                if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(false);
            });
        }
    }

    private void disconnectVXP() {
        if (vxpClient != null) {
            try { vxpClient.disconnect(); } catch (Throwable ignore) {}
        }
        SwingUtilities.invokeLater(() -> {
            if (vxpStatusLbl != null) vxpStatusLbl.setText("Disconnected");
            if (vxpStartBtn != null) vxpStartBtn.setEnabled(true);
            if (vxpStopBtn != null) vxpStopBtn.setEnabled(false);
            if (vxpSendMsgBtn != null) vxpSendMsgBtn.setEnabled(false);
            if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(false);
        });
    }

    private void handleVxpPayload(byte[] payload) {
        VaraPacket packet = VaraPacket.fromBytes(payload);
        if (packet == null) return; // ignore non-VXP
        String src = safeTrim(packet.getSourceCallsign());
        String dst = safeTrim(packet.getDestCallsign());
        // Update tracker and mesh
        if (vxpTracker != null) {
            if (src != null) vxpTracker.logNodeActivity(src);
            if (dst != null) vxpTracker.logNodeActivity(dst);
        }
        if (vxpMesh != null) vxpMesh.animatePacket(packet);
        // Update RX area for MSG
        if (packet.getPacketType() == VaraPacket.PacketType.MSG && vxpRxArea != null) {
            String text;
            try {
                text = new String(packet.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Throwable t) {
                text = "<bin:" + packet.getPayload().length + ">";
            }
            String line = String.format("[%tT] %s -> %s: %s%n", java.time.Instant.now(), src, dst, text);
            SwingUtilities.invokeLater(() -> vxpRxArea.append(line));
        }
    }

    private static String safeTrim(String s) { return s == null ? null : s.trim().toUpperCase(); }

    private void sendVxpMessage() {
        if (vxpClient == null || !vxpClient.isConnected()) return;
        String my = vxpMyCallField != null ? vxpMyCallField.getText().trim().toUpperCase() : "N0CALL";
        String to = vxpDestField != null ? vxpDestField.getText().trim().toUpperCase() : "SERVER";
        String msg = vxpMessageArea != null ? vxpMessageArea.getText() : "";
        if (msg == null) msg = "";
        byte[] payload = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (payload.length > 238) {
            // truncate to one packet for now
            payload = java.util.Arrays.copyOf(payload, 238);
        }
        try {
            // Persist MYCALL/DEST
            try { prefs.put("vxp.mycall", my); prefs.put("vxp.dest", to); } catch (Throwable ignore) {}
            VaraPacket p = new VaraPacket(VaraPacket.PacketType.MSG, my, to, 1, payload);
            vxpClient.sendData(p.toBytes());
            // Local animation and tracker update
            if (vxpTracker != null) { vxpTracker.logNodeActivity(my); vxpTracker.logNodeActivity(to); }
            if (vxpMesh != null) vxpMesh.animatePacket(p);
            // Clear message box optionally
            if (vxpMessageArea != null) vxpMessageArea.setText("");
        } catch (Exception ex) {
            SwingUtilities.invokeLater(() -> {
                if (vxpStatusLbl != null) vxpStatusLbl.setText("Send error: " + ex.getMessage());
            });
        }
    }

    private void sendVxpFile() {
        if (vxpClient == null || !vxpClient.isConnected()) return;
        javax.swing.JFileChooser fc = new javax.swing.JFileChooser();
        if (fc.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        if (f == null || !f.isFile()) return;
        String my = vxpMyCallField != null ? vxpMyCallField.getText().trim().toUpperCase() : "N0CALL";
        String to = vxpDestField != null ? vxpDestField.getText().trim().toUpperCase() : "SERVER";
        if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(false);
        if (vxpFileProgress != null) vxpFileProgress.setValue(0);
        if (vxpFileStatus != null) vxpFileStatus.setText("Preparing " + f.getName());
        // Background thread
        new Thread(() -> {
            try {
                long size = f.length();
                String meta = f.getName() + ":" + size;
                VaraPacket metaPkt = new VaraPacket(VaraPacket.PacketType.FILE_META, my, to, 1,
                        meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                vxpClient.sendData(metaPkt.toBytes());
                // Animate META
                if (vxpMesh != null) vxpMesh.animatePacket(metaPkt);
                if (vxpTracker != null) { vxpTracker.logNodeActivity(my); vxpTracker.logNodeActivity(to); }
                // Stream chunks
                byte[] buf = new byte[238];
                long sent = 0;
                try (java.io.InputStream is = new java.io.BufferedInputStream(new java.io.FileInputStream(f))) {
                    int r;
                    while ((r = is.read(buf)) != -1) {
                        byte[] payload = (r == buf.length) ? buf : java.util.Arrays.copyOf(buf, r);
                        VaraPacket chunk = new VaraPacket(VaraPacket.PacketType.FILE_CHUNK, my, to, 1, payload);
                        vxpClient.sendData(chunk.toBytes());
                        sent += r;
                        int pct = size > 0 ? (int) Math.min(100, Math.round((sent * 100.0) / size)) : 0;
                        final int fpct = pct;
                        SwingUtilities.invokeLater(() -> {
                            if (vxpFileProgress != null) vxpFileProgress.setValue(fpct);
                            if (vxpFileStatus != null) vxpFileStatus.setText("Sending: " + f.getName() + " (" + fpct + "%)");
                        });
                        // animate occasionally
                        if (vxpMesh != null) vxpMesh.animatePacket(chunk);
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    if (vxpFileStatus != null) vxpFileStatus.setText("Send complete: " + f.getName());
                    if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    if (vxpFileStatus != null) vxpFileStatus.setText("Error: " + ex.getMessage());
                    if (vxpSendFileBtn != null) vxpSendFileBtn.setEnabled(true);
                });
            }
        }, "VXP-FileSender").start();
    }

}