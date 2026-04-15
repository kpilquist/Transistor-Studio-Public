package org.example;

import javax.swing.*;
import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.border.EmptyBorder;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import java.awt.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class Main {
    private static final String DIVIDER_LOCATION_KEY = "dividerLocation";
    private static final String TOP_LEFT_LOCATION_KEY = "dividerTopLeftLocation";
    private static final String TOP_RIGHT_LOCATION_KEY = "dividerTopRightLocation";

    private static FooterStatusPanel footerBar;

    // Simple rounded color border for VFO buttons
    private static class RoundedColorBorder extends javax.swing.border.AbstractBorder {
        private final Color color;
        private final int thickness;
        private final int arc;

        public RoundedColorBorder(Color color, int thickness, int arc) {
            this.color = color;
            this.thickness = Math.max(1, thickness);
            this.arc = Math.max(0, arc);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (color == null || color.getAlpha() == 0) return; // transparent/no border
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(thickness));
                int offs = thickness / 2;
                int w = width - thickness;
                int h = height - thickness;
                g2.drawRoundRect(x + offs, y + offs, w, h, arc, arc);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c) {
            // Increase inner padding so the rounded outline has more breathing room
            int pad = Math.max(8, thickness + 6);
            return new Insets(pad, pad, pad, pad);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            Insets i = getBorderInsets(c);
            insets.top = i.top; insets.left = i.left; insets.bottom = i.bottom; insets.right = i.right;
            return insets;
        }
    }

    // Latch to signal when the UI is fully initialized
    public static final CountDownLatch uiInitializedLatch = new CountDownLatch(1);

    public static void setFooterText(String text) {
        if (footerBar != null) {
            SwingUtilities.invokeLater(() -> footerBar.setStatusText(text));
        }
    }

    public static void setFooterVolume(int percent) {
        if (footerBar != null) {
            SwingUtilities.invokeLater(() -> footerBar.setVolumePercent(percent));
        }
    }

    // Update FPS text and indicate cap using a subtle border on the FPS label
    public static void setFooterFps(String text, boolean capActive) {
        if (footerBar == null) return;
        SwingUtilities.invokeLater(() -> footerBar.setFps(text, capActive));
    }

    private static String formatFrequency(long hz) {
        long mhz = hz / 1_000_000;
        long khz = (hz % 1_000_000) / 1_000;
        long h = hz % 1_000;
        return String.format("%d.%03d.%03d Hz", mhz, khz, h);
    }

    public static ScheduledExecutorService backgroundTaskExecutor = Executors.newScheduledThreadPool(
        Runtime.getRuntime().availableProcessors(),
        r -> {
            Thread t = new Thread(r, "Background-Task-Thread");
            t.setDaemon(true);
            return t;
        }
    );

    public static void main(String[] args, List<String> serialPorts) {
        // Initialize transient logging buffer and critical error handler
        try {
            LogBuffer.info("Application starting...");
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    LogBuffer.fatal("Uncaught exception in thread " + t.getName(), e);
                    java.io.File f = LogBuffer.get().dumpToDefaultLocation("fatal");
                    System.err.println("Critical error. Log dumped to: " + f.getAbsolutePath());
                } catch (Exception ex) {
                    LogBuffer.error("Failed to dump fatal log after uncaught exception", ex);
                }
            });
        } catch (Throwable initEx) {
            LogBuffer.error("Failed to initialize logging/exception handler in Main", initEx);
        }
        System.out.println("Main: Starting main method...");

        // Add shutdown hook to ensure proper cleanup of resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Main: Shutdown hook triggered, cleaning up resources...");
            if (backgroundTaskExecutor != null && !backgroundTaskExecutor.isShutdown()) {
                backgroundTaskExecutor.shutdownNow();
                System.out.println("Main: Background task executor shut down.");
            }
            System.out.println("Main: All resources cleaned up.");
        }));

        SwingUtilities.invokeLater(() -> {
            try {
                createAndShowGUI(serialPorts);
            } catch (Exception e) {
                LogBuffer.error("Exception in createAndShowGUI (EDT)", e);
            }
        });
    }

    private static void createAndShowGUI(List<String> serialPorts) {
        try {
            System.out.println("Main: Setting look and feel and applying saved theme...");
            ThemeManager.setupLookAndFeelAndTheme();
            System.out.println("Main: Look and feel and theme set successfully.");
        } catch (Exception e) {
            System.out.println("Main: Error setting look and feel/theme: " + e.getMessage());
            LogBuffer.error("Error setting Look & Feel / Theme", e);
        }

        // Apply UI scaling based on preferences and current screen
        try {
            float scale = UIScaler.applyGlobalScalingFromPrefs();
            System.out.println("Main: UI scaling applied with factor " + scale);
        } catch (Throwable t) {
            LogBuffer.warn("Failed to apply UI scaling, continuing with defaults: " + t.getMessage());
        }

        System.out.println("Main: Creating main application frame...");
        JFrame frame = new JFrame("Transistor Studio");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(400, 300);
        frame.setLocationRelativeTo(null); // Center the frame on the screen
        ImageIcon icon = new ImageIcon("resources/icons/ts.png");
        frame.setIconImage(icon.getImage()); // Set the application icon
        frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
        System.out.println("Main: Main application frame created.");

        // Defer menu bar creation until after panels are initialized so About can access selections

        // Theme Color
        Color adaptiveColor = UIManager.getColor("Panel.background");

        // Top Icon Panel (Icon bar under the menu)
        JPanel topIconPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        topIconPanel.setBackground(adaptiveColor);
        topIconPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(5, 8, 5, 8)
        ));

        // VFO A/B
        topIconPanel.add(new JLabel("VFO:"));
        JToggleButton vfoAButtonTop = new JToggleButton("A");
        JToggleButton vfoBButtonTop = new JToggleButton("B");
        // Improve look for transparent borders
        vfoAButtonTop.setContentAreaFilled(true);
        vfoAButtonTop.setFocusPainted(false);
        vfoBButtonTop.setContentAreaFilled(true);
        vfoBButtonTop.setFocusPainted(false);

        ButtonGroup vfoGroupTop = new ButtonGroup();
        vfoGroupTop.add(vfoAButtonTop);
        vfoGroupTop.add(vfoBButtonTop);
        vfoAButtonTop.setSelected(true);
        topIconPanel.add(vfoAButtonTop);
        topIconPanel.add(vfoBButtonTop);

        // Separate frequency indicators for VFO A and B
        topIconPanel.add(new JLabel(" A:"));
        JLabel vfoAFreqTop = new JLabel("---.---.--- Hz");
        vfoAFreqTop.setFont(new Font("Monospaced", Font.PLAIN, 12));
        topIconPanel.add(vfoAFreqTop);
        topIconPanel.add(new JLabel(" B:"));
        JLabel vfoBFreqTop = new JLabel("---.---.--- Hz");
        vfoBFreqTop.setFont(new Font("Monospaced", Font.PLAIN, 12));
        topIconPanel.add(vfoBFreqTop);

        // S-Meter compact bar
        topIconPanel.add(new JLabel(" S:"));
        JProgressBar sMeterTop = new JProgressBar(0, 255);
        sMeterTop.setPreferredSize(new Dimension(100, 18));
        sMeterTop.setStringPainted(true);
        sMeterTop.setString("0");
        topIconPanel.add(sMeterTop);

        // SWR compact bar
        topIconPanel.add(new JLabel(" SWR:"));
        JProgressBar swrMeterTop = new JProgressBar(0, 255);
        swrMeterTop.setPreferredSize(new Dimension(100, 18));
        swrMeterTop.setStringPainted(true);
        swrMeterTop.setString("1.00");
        topIconPanel.add(swrMeterTop);

        // Power button
        topIconPanel.add(new JLabel(" PWR:"));
        JToggleButton powerToggleTop = new JToggleButton("--");
        powerToggleTop.setEnabled(false);
        powerToggleTop.setToolTipText("Radio power: ON/OFF (radio only replies after it is fully ON)");
        topIconPanel.add(powerToggleTop);

        // TX Status with thick rounded border + PTT mode icon (CAT vs Serial)
        topIconPanel.add(new JLabel(" TX:"));
        JLabel txStatusLabel = new JLabel("Ready");
        txStatusLabel.setOpaque(true);
        txStatusLabel.setBackground(UIManager.getColor("Panel.background"));
        txStatusLabel.setBorder(new RoundedColorBorder(new Color(0, 160, 0), 4, 12));
        txStatusLabel.setPreferredSize(new Dimension(90, 26));
        txStatusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        txStatusLabel.setToolTipText("Transmit status");
        topIconPanel.add(txStatusLabel);

        // Simple icons for mode
        Icon serialIcon = new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(200,200,200));
                g2.fillRoundRect(x+1, y+3, 12, 8, 3, 3); // body
                g2.setColor(new Color(130,130,130));
                g2.drawRoundRect(x+1, y+3, 12, 8, 3, 3);
                g2.setColor(new Color(180,180,0));
                g2.fillRect(x+4, y+1, 6, 3); // connector
                g2.dispose();
            }
        };
        Icon catIcon = new Icon() {
            public int getIconWidth() { return 14; }
            public int getIconHeight() { return 14; }
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(120,160,220));
                g2.fillRoundRect(x+1, y+1, 12, 12, 4, 4); // chip
                g2.setColor(new Color(240,240,240));
                g2.fillRect(x+5, y+5, 4, 4);
                g2.setColor(new Color(60,100,160));
                g2.drawRoundRect(x+1, y+1, 12, 12, 4, 4);
                // pins
                g2.drawLine(x+3, y, x+3, y+2);
                g2.drawLine(x+6, y, x+6, y+2);
                g2.drawLine(x+9, y, x+9, y+2);
                g2.drawLine(x+3, y+14, x+3, y+12);
                g2.drawLine(x+6, y+14, x+6, y+12);
                g2.drawLine(x+9, y+14, x+9, y+12);
                g2.dispose();
            }
        };
        JLabel pttModeLabel = new JLabel("CAT", catIcon, SwingConstants.LEFT);
        pttModeLabel.setToolTipText("TX control mode");
        topIconPanel.add(new JLabel(" Mode:"));
        topIconPanel.add(pttModeLabel);

        // CAT TCP client indicator
        topIconPanel.add(Box.createHorizontalStrut(12));
        CatStatusIndicator catIndicator = new CatStatusIndicator();
        topIconPanel.add(catIndicator);
        try {
            CatServerManager mgr = CatServerManager.getInstance();
            // Set initial based on server running state
            catIndicator.setServerRunning(mgr.isRunning());
            mgr.setStatusListener(running -> SwingUtilities.invokeLater(() -> catIndicator.setServerRunning(running)));
            mgr.setClientCountListener(cnt -> SwingUtilities.invokeLater(() -> catIndicator.setClientCount(cnt)));
        } catch (Throwable t) {
            // If anything goes wrong, keep indicator in default state; log silently
            System.err.println("Failed to wire CAT indicator: " + t.getMessage());
        }

        frame.setLayout(new BorderLayout());
        frame.add(topIconPanel, BorderLayout.NORTH);

        // Create top and bottom panels
        JPanel topPanel = new JPanel();
        topPanel.setBackground(adaptiveColor);


        JPanel leftPanel = new JPanel(new BorderLayout());
        leftPanel.setBackground(adaptiveColor);
        leftPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
        
        ChatBoxPanel chatBoxPanel = new ChatBoxPanel();
        // Ensure chat area also has a sensible minimum so divider never fully hides it, but allow it to yield to Radio Control
        chatBoxPanel.setMinimumSize(new Dimension(150, 80));
        
        JPanel rightPanel = new JPanel(new BorderLayout());
        // Allow right panel (tabs/decoders) to shrink aggressively so Radio Control keeps priority
        rightPanel.setMinimumSize(new Dimension(150, 100));
        // Add DeviceListPanel back to keep the WASAPI and Mark Pen tabs
        DeviceListPanel deviceListPanel = new DeviceListPanel(serialPorts, chatBoxPanel);
        
        // Move Radio Control section to the left panel
        JPanel radioControlLeft = deviceListPanel.detachRadioControlPanelFromTabs();
        if (radioControlLeft != null) {
            // Add a small left margin so controls don't butt right against the divider
            radioControlLeft.setBorder(new EmptyBorder(0, 8, 0, 6));
            JScrollPane rcScroll = new JScrollPane(radioControlLeft,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            rcScroll.setBorder(BorderFactory.createEmptyBorder());
            // Allow the panel to squish down rather than pushing back on the divider
            int minRcWidth = 50; // tweak as desired
            Dimension minRcDim = new Dimension(minRcWidth, 100);
            radioControlLeft.setMinimumSize(minRcDim);
            rcScroll.setMinimumSize(minRcDim);
            leftPanel.setMinimumSize(new Dimension(minRcWidth, 100));
            // Slightly faster scroll for tall content
            rcScroll.getVerticalScrollBar().setUnitIncrement(16);
            leftPanel.add(rcScroll, BorderLayout.CENTER);
        }
        rightPanel.add(deviceListPanel, BorderLayout.CENTER);

        // Now that UI panels are created, set up the menu bar so About can read selections
        frame.setJMenuBar(MenuBarFactory.createMenuBar(deviceListPanel));


        // Wire top icon bar controls to RadioControl
        RadioControl rc = deviceListPanel.getRadioControl();
        // Provide RadioControl to CAT IP server manager and autostart if enabled (defaults to ON)
        try { CatServerManager.getInstance().setRadioControl(rc); } catch (Throwable ignore) {}
        // Provide RadioControl to WebSocket Remote Control manager and autostart if enabled (defaults to ON)
        try { RemoteControlManager.getInstance().setRadioControl(rc); } catch (Throwable ignore) {}

        // PTT strategy: click the Mode label to toggle Auto -> Serial (RTS) -> CAT
        final Preferences mainPrefs = Preferences.userNodeForPackage(Main.class);
        final String PREF_PTT_STRATEGY = "pttStrategy"; // values: AUTO, SERIAL, CAT
        final RadioControl.PttStrategy[] stratHolder = new RadioControl.PttStrategy[]{RadioControl.PttStrategy.AUTO};
        // Load saved strategy
        try {
            String saved = mainPrefs.get(PREF_PTT_STRATEGY, "AUTO");
            RadioControl.PttStrategy s = "SERIAL".equalsIgnoreCase(saved) ? RadioControl.PttStrategy.FORCE_SERIAL
                    : ("CAT".equalsIgnoreCase(saved) ? RadioControl.PttStrategy.FORCE_CAT : RadioControl.PttStrategy.AUTO);
            stratHolder[0] = s;
            try { rc.setPttStrategy(s); } catch (Throwable ignore) {}
        } catch (Throwable ignore) {}
        Runnable updatePttModeLabel = () -> {
            RadioControl.PttStrategy s = stratHolder[0];
            boolean haveRts = false; String rtsName = null;
            try { haveRts = rc.isUsingHardwarePTT(); rtsName = rc.getPttPortName(); } catch (Throwable ignore) {}
            switch (s) {
                case FORCE_SERIAL:
                    pttModeLabel.setText("Serial");
                    pttModeLabel.setIcon(serialIcon);
                    if (haveRts) {
                        pttModeLabel.setToolTipText("Using hardware PTT via serial" + (rtsName != null ? (" (" + rtsName + ")") : ""));
                    } else {
                        pttModeLabel.setToolTipText("Serial PTT forced but unavailable — will fall back to CAT if needed");
                    }
                    break;
                case FORCE_CAT:
                    pttModeLabel.setText("CAT");
                    pttModeLabel.setIcon(catIcon);
                    pttModeLabel.setToolTipText("Using CAT TX commands (TX1;/TX0;)");
                    break;
                default: // AUTO
                    if (haveRts) {
                        pttModeLabel.setText("Auto");
                        pttModeLabel.setIcon(serialIcon);
                        pttModeLabel.setToolTipText("Auto: preferring Serial PTT" + (rtsName != null ? (" (" + rtsName + ")") : ""));
                    } else {
                        pttModeLabel.setText("Auto");
                        pttModeLabel.setIcon(catIcon);
                        pttModeLabel.setToolTipText("Auto: falling back to CAT (no Serial PTT)");
                    }
                    break;
            }
        };
        updatePttModeLabel.run();
        pttModeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        pttModeLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                RadioControl.PttStrategy next;
                if (stratHolder[0] == RadioControl.PttStrategy.AUTO) next = RadioControl.PttStrategy.FORCE_SERIAL;
                else if (stratHolder[0] == RadioControl.PttStrategy.FORCE_SERIAL) next = RadioControl.PttStrategy.FORCE_CAT;
                else next = RadioControl.PttStrategy.AUTO;
                stratHolder[0] = next;
                try { rc.setPttStrategy(next); } catch (Throwable ignore) {}
                String save = next == RadioControl.PttStrategy.FORCE_SERIAL ? "SERIAL"
                        : (next == RadioControl.PttStrategy.FORCE_CAT ? "CAT" : "AUTO");
                try { mainPrefs.put(PREF_PTT_STRATEGY, save); } catch (Throwable ignore) {}
                updatePttModeLabel.run();
            }
        });

        // Guard to avoid feedback loops between UI and radio callbacks
        final boolean[] topUpdating = new boolean[]{false};

        // Track current active VFO and TX state for border updates
        final char[] activeVFO = new char[]{'A'};
        final boolean[] isTx = new boolean[]{false};

        // Debounce/hold for VFO buttons to avoid rapid toggling that can desync UI and radio
        final int VFO_HOLD_MS = 250; // reasonable interval; adjust if needed
        final boolean[] vfoHeld = new boolean[]{false};
        final javax.swing.Timer[] vfoHoldTimer = new javax.swing.Timer[]{null};
        final Runnable[] releaseVFOHold = new Runnable[1];
        final Runnable[] startVFOHold = new Runnable[1];
        releaseVFOHold[0] = () -> {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> releaseVFOHold[0].run());
                return;
            }
            vfoHeld[0] = false;
            vfoAButtonTop.setEnabled(true);
            vfoBButtonTop.setEnabled(true);
            vfoAButtonTop.setCursor(Cursor.getDefaultCursor());
            vfoBButtonTop.setCursor(Cursor.getDefaultCursor());
        };
        startVFOHold[0] = () -> {
            if (!SwingUtilities.isEventDispatchThread()) {
                SwingUtilities.invokeLater(() -> startVFOHold[0].run());
                return;
            }
            vfoHeld[0] = true;
            vfoAButtonTop.setEnabled(false);
            vfoBButtonTop.setEnabled(false);
            vfoAButtonTop.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            vfoBButtonTop.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            if (vfoHoldTimer[0] != null && vfoHoldTimer[0].isRunning()) {
                vfoHoldTimer[0].stop();
            }
            vfoHoldTimer[0] = new javax.swing.Timer(VFO_HOLD_MS, e -> releaseVFOHold[0].run());
            vfoHoldTimer[0].setRepeats(false);
            vfoHoldTimer[0].start();
        };

        // Helper to update rounded borders around VFO frequency displays (labels)
        Runnable updateVFOBorders = () -> {
            SwingUtilities.invokeLater(() -> {
                boolean tx = isTx[0];
                char aV = activeVFO[0];
                Color transparent = new Color(0,0,0,0);
                // Darker blue-grey for active VFO (non-TX)
                Color yellow = new Color(90, 110, 130);
                Color red = Color.RED;
                int thickness = 2;
                int arc = 8;

                // Determine colors per label
                Color aBorder = transparent;
                Color bBorder = transparent;
                if (aV == 'A') {
                    aBorder = tx ? red : yellow;
                } else {
                    bBorder = tx ? red : yellow;
                }

                vfoAFreqTop.setBorder(new RoundedColorBorder(aBorder, thickness, arc));
                vfoBFreqTop.setBorder(new RoundedColorBorder(bBorder, thickness, arc));
                vfoAFreqTop.repaint();
                vfoBFreqTop.repaint();
            });
        };

        // Initial borders
        updateVFOBorders.run();

        // VFO button actions using ItemListeners (act only when selected) with debounce/hold
        vfoAButtonTop.addItemListener(evt -> {
            if (topUpdating[0]) return; // ignore programmatic changes
            if (evt.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                if (vfoHeld[0]) return; // still holding, ignore
                startVFOHold[0].run();
                activeVFO[0] = 'A';
                updateVFOBorders.run();
                rc.setActiveVFO('A');
            }
        });
        vfoBButtonTop.addItemListener(evt -> {
            if (topUpdating[0]) return; // ignore programmatic changes
            if (evt.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                if (vfoHeld[0]) return; // still holding, ignore
                startVFOHold[0].run();
                activeVFO[0] = 'B';
                updateVFOBorders.run();
                rc.setActiveVFO('B');
            }
        });

        // Reflect VFO changes from radio
        rc.setVFOListener(v -> SwingUtilities.invokeLater(() -> {
            topUpdating[0] = true;
            try {
                activeVFO[0] = v;
                if (v == 'B') {
                    if (!vfoBButtonTop.isSelected()) vfoBButtonTop.setSelected(true);
                } else {
                    if (!vfoAButtonTop.isSelected()) vfoAButtonTop.setSelected(true);
                }
                updateVFOBorders.run();
            } finally {
                topUpdating[0] = false;
            }
        }));

        // TX status listener for red border while transmitting
        rc.setTXListener(tx -> {
            isTx[0] = tx != null && tx;
            updateVFOBorders.run();
        });

        // Per-VFO frequency listeners for top bar labels
        rc.setVFOAFrequencyListener(freq -> SwingUtilities.invokeLater(() -> vfoAFreqTop.setText(formatFrequency(freq))));
        rc.setVFOBFrequencyListener(freq -> SwingUtilities.invokeLater(() -> vfoBFreqTop.setText(formatFrequency(freq))));

        // S-Meter listener
        rc.setSMeterListener(val -> SwingUtilities.invokeLater(() -> {
            sMeterTop.setValue(val);
            sMeterTop.setString(Integer.toString(val));
        }));
        // SWR listener (approximate mapping: 1.00 .. ~6.10)
        rc.setSWRListener(val -> SwingUtilities.invokeLater(() -> {
            swrMeterTop.setValue(val);
            double swr = 1.0 + (val / 50.0);
            swrMeterTop.setString(String.format("%.2f", swr));
        }));

        // ===== TX Status Indicator Wiring =====
        final boolean[] catConnected = new boolean[]{false};
        final Boolean[] radioOn = new Boolean[]{null};
        final boolean[] audioReady = new boolean[]{false};

        Runnable recomputeTxStatus = () -> SwingUtilities.invokeLater(() -> {
            boolean txNow = isTx[0];
            if (txNow) {
                txStatusLabel.setText("TX");
                txStatusLabel.setBorder(new RoundedColorBorder(Color.RED, 4, 12));
                txStatusLabel.setToolTipText("Transmitting");
                return;
            }
            // Determine readiness
            boolean connectedNow = catConnected[0];
            Boolean pwr = radioOn[0];
            boolean audioOk = audioReady[0];
            boolean powerKnownOn = (pwr != null && pwr);
            boolean hasProblem = !connectedNow || !powerKnownOn || !audioOk;
            if (hasProblem) {
                txStatusLabel.setText("Problem");
                txStatusLabel.setBorder(new RoundedColorBorder(new Color(200, 160, 0), 4, 12));
                StringBuilder tip = new StringBuilder("Issues: ");
                boolean first = true;
                if (!connectedNow) { tip.append("radio not connected"); first = false; }
                if (!powerKnownOn) { if (!first) tip.append(", "); tip.append("radio OFF or unknown"); first = false; }
                if (!audioOk) { if (!first) tip.append(", "); tip.append("sound card unavailable"); }
                txStatusLabel.setToolTipText(tip.toString());
            } else {
                txStatusLabel.setText("Ready");
                txStatusLabel.setBorder(new RoundedColorBorder(new Color(0, 160, 0), 4, 12));
                txStatusLabel.setToolTipText("Ready to transmit");
            }
        });

        // Initialize audioReady based on current selection
        try {
            String selOut = deviceListPanel.getSelectedOutputDevice();
            audioReady[0] = selOut != null && !selOut.startsWith("No Audio");
        } catch (Throwable ignore) {}
        recomputeTxStatus.run();

        // Listen to output device changes
        deviceListPanel.addOutputDeviceSelectionListener(e -> {
            String sel = deviceListPanel.getSelectedOutputDevice();
            audioReady[0] = sel != null && !sel.startsWith("No Audio");
            recomputeTxStatus.run();
        });

        // Helper to keep the power button label/state correct
        Runnable updatePowerButton = () -> SwingUtilities.invokeLater(() -> {
            boolean connectedNow = catConnected[0];
            if (!connectedNow) {
                powerToggleTop.setEnabled(false);
                powerToggleTop.setText("--");
                powerToggleTop.setSelected(false);
                return;
            }
            powerToggleTop.setEnabled(true);
            Boolean pwr = radioOn[0];
            boolean on = (pwr != null && pwr);
            powerToggleTop.setText(on ? "PWR OFF" : "PWR ON");
            powerToggleTop.setSelected(on);
        });

        // Wire click: OFF/unknown -> ON, ON -> OFF
        powerToggleTop.addActionListener(e -> {
            Boolean pwr = radioOn[0];
            if (Boolean.TRUE.equals(pwr)) {
                try { rc.powerOff(); } catch (Throwable ignore) {}
            } else {
                try { rc.powerOn(); } catch (Throwable ignore) {}
            }
            // Optimistically update label; final state will sync via listener
            updatePowerButton.run();
        });

        // Listen to CAT connection changes
        rc.setConnectionListener(connected -> {
            catConnected[0] = Boolean.TRUE.equals(connected);
            recomputeTxStatus.run();
            // Update UI bits on EDT
            SwingUtilities.invokeLater(() -> {
                // Enable/disable power toggle and text
                updatePowerButton.run();
                if (Boolean.TRUE.equals(connected)) {
                    // Request power status refresh when (re)connected
                    try { rc.requestPowerStatus(); } catch (Throwable ignore) {}
                }
                // Update mode label (reflect current strategy and availability)
                updatePttModeLabel.run();
            });
        });
        // Listen to power status
        rc.setPowerStatusListener(on -> {
            radioOn[0] = on;
            updatePowerButton.run();
            recomputeTxStatus.run();
        });
        // Listen to TX state
        rc.setTXListener(tx -> {
            isTx[0] = tx != null && tx;
            recomputeTxStatus.run();
            updateVFOBorders.run();
        });

        Preferences prefs = Preferences.userNodeForPackage(Main.class);

        // Helper: load best saved divider for current state, with graceful fallback
        java.util.function.BiFunction<String, Integer, Integer> loadDivider = (baseKey, defVal) -> {
            int state = frame.getExtendedState();
            boolean isMax = (state & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
            int v = -1;
            if (isMax) {
                v = prefs.getInt(baseKey + "_maximized", -1);
                if (v < 0) v = prefs.getInt(baseKey + "_normal", -1);
            } else {
                v = prefs.getInt(baseKey + "_normal", -1);
                if (v < 0) v = prefs.getInt(baseKey + "_maximized", -1);
            }
            if (v < 0) v = prefs.getInt(baseKey, -1);
            return v >= 0 ? v : defVal;
        };
        // Helper: save divider for both the current state suffix and the base key
        java.util.function.BiConsumer<String, Integer> saveDivider = (baseKey, value) -> {
            int state = frame.getExtendedState();
            boolean isMax = (state & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
            if (isMax) {
                prefs.putInt(baseKey + "_maximized", value);
            } else {
                prefs.putInt(baseKey + "_normal", value);
            }
            // Also keep a generic last-known value
            prefs.putInt(baseKey, value);
        };

        JSplitPane leftCenterSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, chatBoxPanel);
        leftCenterSplitPane.setContinuousLayout(true);
        // Disable one-touch arrows on this split pane to prevent overlap over Radio Control
        leftCenterSplitPane.setOneTouchExpandable(false);
        leftCenterSplitPane.setDividerSize(8);
        leftCenterSplitPane.setResizeWeight(0.0); // favor keeping left min width intact when resizing
        // Enforce a much smaller minimum width for the left (radio controls) side so it can squish
        int minLeft = Math.max(leftPanel.getMinimumSize() != null ? leftPanel.getMinimumSize().width : 0, 50);
        leftPanel.setMinimumSize(new Dimension(minLeft, 100));
        leftCenterSplitPane.setMinimumSize(new Dimension(minLeft + 100, 100)); // ensure splitpane itself can't be squeezed too far
        int defaultTl = Toolkit.getDefaultToolkit().getScreenSize().width / 2;
        int savedTLDividerLocation = loadDivider.apply(TOP_LEFT_LOCATION_KEY, defaultTl);
        leftCenterSplitPane.setDividerLocation(Math.max(savedTLDividerLocation, minLeft));
        // Clamp divider if user drags too far left so Radio Control is never covered
        leftCenterSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            if (!frame.isVisible()) return; // Ignore layout manager initialization events
            int loc = leftCenterSplitPane.getDividerLocation();
            if (loc < minLeft) {
                SwingUtilities.invokeLater(() -> leftCenterSplitPane.setDividerLocation(minLeft));
            } else {
                saveDivider.accept(TOP_LEFT_LOCATION_KEY, loc);
            }
        });
        // Also clamp on resize/layout changes (e.g., when maximizing/restoring or DPI changes)
        leftCenterSplitPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int loc = leftCenterSplitPane.getDividerLocation();
                if (loc < minLeft) {
                    leftCenterSplitPane.setDividerLocation(minLeft);
                }
            }
        });

        JSplitPane topSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftCenterSplitPane, rightPanel);
        topSplitPane.setContinuousLayout(true);
        // Disable one-touch arrows to avoid overlay over the left controls
        topSplitPane.setOneTouchExpandable(false);
        topSplitPane.setDividerSize(8);
        topSplitPane.setResizeWeight(0.67);
        // Enforce a minimum width for the left side so right panel never covers controls
        int minLeftTop = Math.max(leftCenterSplitPane.getMinimumSize() != null ? leftCenterSplitPane.getMinimumSize().width : 0, minLeft);
        int savedTRDividerLocation = loadDivider.apply(TOP_RIGHT_LOCATION_KEY, Math.max(400, minLeftTop));
        topSplitPane.setDividerLocation(Math.max(savedTRDividerLocation, minLeftTop));
        topSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            if (!frame.isVisible()) return; // Ignore layout manager initialization events
            int loc = topSplitPane.getDividerLocation();
            if (loc < minLeftTop) {
                SwingUtilities.invokeLater(() -> topSplitPane.setDividerLocation(minLeftTop));
            } else {
                saveDivider.accept(TOP_RIGHT_LOCATION_KEY, loc);
            }
        });
        // Also clamp on resize/layout changes
        topSplitPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int loc = topSplitPane.getDividerLocation();
                if (loc < minLeftTop) {
                    topSplitPane.setDividerLocation(minLeftTop);
                }
            }
        });
        topPanel.setLayout(new BorderLayout());
        topPanel.add(topSplitPane, BorderLayout.CENTER);

        // Create bottom panel
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(adaptiveColor);
        bottomPanel.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));

        // Create a container for the visualizer
        JPanel visualizerContainer = new JPanel(new BorderLayout());
        visualizerContainer.setOpaque(false);

        // Always use CPU-based Waterfall (GPU mode removed)
        SpectrogramDisplay waterfall = new Waterfall(1024, 300, 24000);
        visualizerContainer.add(waterfall.getComponent(), BorderLayout.CENTER);
        deviceListPanel.setWaterfall(waterfall);

        // Create toolbar for waterfall controls
        JToolBar waterfallToolbar = new JToolBar();
        waterfallToolbar.setFloatable(false);

        JToggleButton selectModeBtn = new JToggleButton("Select");
        JToggleButton zoomModeBtn = new JToggleButton("Zoom");
        JButton resetZoomBtn = new JButton("Reset Zoom");

        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(selectModeBtn);
        modeGroup.add(zoomModeBtn);
        selectModeBtn.setSelected(true);

        final SpectrogramDisplay finalWaterfall = waterfall;
        // Persist waterfall mode (Select/Zoom)
        final Preferences wfPrefs = Preferences.userNodeForPackage(Main.class);
        selectModeBtn.addActionListener(e -> {
            finalWaterfall.setMode(SpectrogramDisplay.Mode.SELECTION);
            try { wfPrefs.put("waterfallMode", "SELECT"); } catch (Throwable ignore) {}
        });
        zoomModeBtn.addActionListener(e -> {
            finalWaterfall.setMode(SpectrogramDisplay.Mode.ZOOM);
            try { wfPrefs.put("waterfallMode", "ZOOM"); } catch (Throwable ignore) {}
        });
        resetZoomBtn.addActionListener(e -> finalWaterfall.resetView());
        // Restore last mode
        try {
            String savedMode = wfPrefs.get("waterfallMode", "SELECT");
            if ("ZOOM".equalsIgnoreCase(savedMode)) {
                zoomModeBtn.setSelected(true);
                finalWaterfall.setMode(SpectrogramDisplay.Mode.ZOOM);
            } else {
                selectModeBtn.setSelected(true);
                finalWaterfall.setMode(SpectrogramDisplay.Mode.SELECTION);
            }
        } catch (Throwable ignore) {}

        waterfallToolbar.add(selectModeBtn);
        waterfallToolbar.add(zoomModeBtn);

        // Move Digital Mode selector here (to the right of Select and Zoom)
        JComboBox<String> digitalModeCombo = deviceListPanel.getDigitalModeComboBox();
        if (digitalModeCombo != null) {
            // Keep compact and left-aligned
            digitalModeCombo.setPrototypeDisplayValue("RTTY-170"); // bounds width reasonably
            Dimension pref = digitalModeCombo.getPreferredSize();
            int w = Math.min(160, Math.max(110, pref != null ? pref.width : 130));
            int h = pref != null ? pref.height : 24;
            Dimension fixed = new Dimension(w, h);
            digitalModeCombo.setPreferredSize(fixed);
            digitalModeCombo.setMaximumSize(fixed);
            digitalModeCombo.setAlignmentX(Component.LEFT_ALIGNMENT);

            waterfallToolbar.add(Box.createHorizontalStrut(6));
            waterfallToolbar.add(new JLabel("Mode:"));
            waterfallToolbar.add(Box.createHorizontalStrut(4));
            waterfallToolbar.add(digitalModeCombo);
        }

        waterfallToolbar.add(Box.createHorizontalStrut(10));
        waterfallToolbar.add(resetZoomBtn);

        visualizerContainer.add(waterfallToolbar, BorderLayout.NORTH);

        // Add the container to the bottom panel center
        bottomPanel.add(visualizerContainer, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topPanel, bottomPanel);
        int savedDividerLocation = loadDivider.apply(DIVIDER_LOCATION_KEY, 400);
        splitPane.setDividerLocation(savedDividerLocation);
        splitPane.setResizeWeight(0.67);
        splitPane.setDividerSize(5);
        splitPane.setOneTouchExpandable(true);
        // Persist vertical divider whenever it changes
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            if (!frame.isVisible()) return; // Ignore layout manager initialization events
            saveDivider.accept(DIVIDER_LOCATION_KEY, splitPane.getDividerLocation());
        });
        frame.add(splitPane);

        footerBar = new FooterStatusPanel();
        bottomPanel.add(footerBar, BorderLayout.SOUTH);

        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                // Save clamped divider positions so we never persist an under-minimum value
                int tlNow = leftCenterSplitPane.getDividerLocation();
                int clampedTl = Math.max(tlNow, minLeft);
                saveDivider.accept(TOP_LEFT_LOCATION_KEY, clampedTl);
                saveDivider.accept(TOP_RIGHT_LOCATION_KEY, topSplitPane.getDividerLocation());
                saveDivider.accept(DIVIDER_LOCATION_KEY, splitPane.getDividerLocation());

                // Stop remote WebSocket server
                try { RemoteControlManager.getInstance().stop(); } catch (Throwable ignore) {}

                // Shutdown executors gracefully
                System.out.println("Shutting down executors...");
                try {
                    backgroundTaskExecutor.shutdown();
                    if (!backgroundTaskExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                        backgroundTaskExecutor.shutdownNow();
                    }
                    System.out.println("Executors shut down successfully");
                } catch (InterruptedException ex) {
                    backgroundTaskExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                    System.err.println("Executor shutdown interrupted");
                }
            }

            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                // Restore saved dividers with graceful fallback and clamp the left split to keep Radio Controls visible
                int defaultTl = Toolkit.getDefaultToolkit().getScreenSize().width / 2;
                int tl = loadDivider.apply(TOP_LEFT_LOCATION_KEY, defaultTl);
                if (tl < 100) tl = defaultTl; // Recover from previously corrupted saved states
                leftCenterSplitPane.setDividerLocation(Math.max(tl, minLeft));

                int tr = loadDivider.apply(TOP_RIGHT_LOCATION_KEY, 400);
                topSplitPane.setDividerLocation(tr);

                int v = loadDivider.apply(DIVIDER_LOCATION_KEY, 400);
                splitPane.setDividerLocation(v);
            }
        });

        System.out.println("Main: Making main application frame visible...");
        frame.setVisible(true);
        System.out.println("Main: Main application frame is now visible.");

        // First 3 runs: prompt user about UI scaling settings
        try {
            Preferences obPrefs = Preferences.userNodeForPackage(DeviceListPanel.class);
            boolean suppressed = obPrefs.getBoolean("onboarding.scalePrompt.suppressed", false);
            int shown = obPrefs.getInt("onboarding.scalePrompt.count", 0);
            if (!suppressed && shown < 3) {
                obPrefs.putInt("onboarding.scalePrompt.count", shown + 1);
                LogBuffer.info("Showing UI scaling onboarding prompt (show #" + (shown + 1) + ")");
                SwingUtilities.invokeLater(() -> {
                    Object[] options = new Object[]{
                            "Open Display Settings",
                            "Remind Me Later",
                            "Don't show again"
                    };
                    String msg = "Tip: You can adjust the app's UI size for your screen.\n" +
                            "Open Settings → Display to enable Auto-scale (based on your screen)\n" +
                            "or set a Manual % that feels right.\n\n" +
                            "Would you like to open Display Settings now?";
                    int res = JOptionPane.showOptionDialog(
                            frame,
                            msg,
                            "Adjust UI Scaling",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.INFORMATION_MESSAGE,
                            null,
                            options,
                            options[0]
                    );
                    try {
                        if (res == 0) { // Open Display Settings
                            LogBuffer.info("User chose to open Display Settings from onboarding prompt");
                            SettingsDialog dialog = new SettingsDialog(frame);
                            dialog.selectTabByTitle("Display");
                            dialog.setVisible(true);
                        } else if (res == 2) { // Don't show again
                            LogBuffer.info("User chose 'Don't show again' for scaling prompt");
                            prefs.putBoolean("onboarding.scalePrompt.suppressed", true);
                        } else {
                            LogBuffer.info("User deferred scaling prompt (Remind Me Later)");
                        }
                    } catch (Throwable ex) {
                        LogBuffer.warn("Failed handling onboarding scaling prompt selection: " + ex.getMessage());
                    }
                });
            }
        } catch (Throwable t) {
            LogBuffer.warn("Failed to process onboarding scaling prompt: " + t.getMessage());
        }

        // Signal that the UI is fully initialized
        System.out.println("Main: UI fully initialized, counting down latch...");
        uiInitializedLatch.countDown();
        System.out.println("Main: uiInitializedLatch counted down.");
    }
}
