package org.example;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

/**
 * Simple application Settings dialog. Currently provides placeholder tabs and can be expanded later.
 */
public class SettingsDialog extends JDialog {

    private final JTabbedPane tabs;

    public SettingsDialog(Frame owner) {
        super(owner, "Settings", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        tabs = new JTabbedPane();
        tabs.addTab("General", createGeneralPanel());
        tabs.addTab("Waterfall", createWaterfallPanel());
        tabs.addTab("Digital", createDigitalPanel());
        tabs.addTab("VARA", createVARAPanel());
        tabs.addTab("Boundary", createBoundaryPanel());
        tabs.addTab("Safety", createSafetyPanel());
        tabs.addTab("CAT Server", createCatServerPanel());
        tabs.addTab("Help", createHelpPanel());
        tabs.addTab("Display", createDisplayPanel());

        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        content.add(createFooterBar(), BorderLayout.SOUTH);

        setContentPane(content);
        setPreferredSize(new Dimension(600, 450));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Selects the tab with the given title, if present. */
    public void selectTabByTitle(String title) {
        if (title == null) return;
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (title.equalsIgnoreCase(tabs.getTitleAt(i))) {
                tabs.setSelectedIndex(i);
                return;
            }
        }
    }

    private JPanel createGeneralPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Transistor Studio Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel desc = new JLabel("Adjust application preferences. More options will appear in future updates.");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel themeHint = new JPanel(new FlowLayout(FlowLayout.LEFT));
        themeHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        themeHint.add(new JLabel("Theme: "));
        JButton themeButton = new JButton("Change via Theme menu");
        themeButton.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Use the top menu: Theme → select your preferred theme.",
                "Theme Settings",
                JOptionPane.INFORMATION_MESSAGE));
        themeHint.add(themeButton);

        panel.add(title);
        panel.add(Box.createVerticalStrut(8));
        panel.add(desc);
        panel.add(Box.createVerticalStrut(12));
        panel.add(themeHint);

        // Show/Hide Serial Console
        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
        boolean showSerialDefault = prefs.getBoolean("showSerialConsole", false);
        JCheckBox showSerialCb = new JCheckBox("Show Serial Console (requires reopen)", showSerialDefault);
        showSerialCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        showSerialCb.addActionListener(e -> prefs.putBoolean("showSerialConsole", showSerialCb.isSelected()));
        panel.add(Box.createVerticalStrut(12));
        panel.add(showSerialCb);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createWaterfallPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel info = new JLabel("Waterfall display settings will be configurable here.");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(info);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createDigitalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel info = new JLabel("Digital mode settings will be configurable here.");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(info);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createVARAPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);

        JLabel title = new JLabel("VARA File Transfer");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        JCheckBox autosaveCb = new JCheckBox("Autosave incoming files (default ON)");
        autosaveCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        autosaveCb.setSelected(prefs.getBoolean("vara.autosaveIncoming", true));
        autosaveCb.addActionListener(e -> prefs.putBoolean("vara.autosaveIncoming", autosaveCb.isSelected()));
        panel.add(autosaveCb);

        panel.add(Box.createVerticalStrut(8));
        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderRow.add(new JLabel("Incoming files folder:"));
        JTextField folderField = new JTextField(32);
        String userHome = System.getProperty("user.home");
        String defaultDir = userHome + java.io.File.separator + "Documents" + java.io.File.separator + "TransistorStudio" + java.io.File.separator + "VARA";
        folderField.setText(prefs.get("vara.incomingFolder", defaultDir));
        JButton browseBtn = new JButton("Browse…");
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(folderField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                java.io.File dir = fc.getSelectedFile();
                folderField.setText(dir.getAbsolutePath());
                prefs.put("vara.incomingFolder", dir.getAbsolutePath());
            }
        });
        folderRow.add(folderField);
        folderRow.add(browseBtn);
        panel.add(folderRow);

        panel.add(Box.createVerticalStrut(8));
        JCheckBox whitelistEnable = new JCheckBox("Enable whitelist (accept files only from listed callsigns)");
        whitelistEnable.setAlignmentX(Component.LEFT_ALIGNMENT);
        whitelistEnable.setSelected(prefs.getBoolean("vara.whitelistEnabled", false));
        whitelistEnable.addActionListener(e -> prefs.putBoolean("vara.whitelistEnabled", whitelistEnable.isSelected()));
        panel.add(whitelistEnable);

        JTextArea whitelistArea = new JTextArea(5, 40);
        whitelistArea.setLineWrap(true);
        whitelistArea.setWrapStyleWord(true);
        whitelistArea.setText(prefs.get("vara.whitelistCalls", ""));
        JScrollPane wlScroll = new JScrollPane(whitelistArea);
        wlScroll.setBorder(BorderFactory.createTitledBorder("Whitelisted callsigns (comma or newline separated)"));
        wlScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(wlScroll);

        JButton saveWhitelist = new JButton("Save Whitelist");
        saveWhitelist.setAlignmentX(Component.LEFT_ALIGNMENT);
        saveWhitelist.addActionListener(e -> prefs.put("vara.whitelistCalls", whitelistArea.getText().trim()));
        panel.add(Box.createVerticalStrut(6));
        panel.add(saveWhitelist);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createBoundaryPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Transmit Boundary (Spectrum Protection)");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        JLabel desc = new JLabel("Choose your license class and enable Digital and/or Voice enforcement. Default: Off.");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(desc);
        panel.add(Box.createVerticalStrut(12));

        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);

        // Row 1: License selector
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row1.add(new JLabel("License Class:"));
        String[] licenses = new String[]{"Off", "Technician", "General", "Extra"};
        JComboBox<String> licenseCombo = new JComboBox<>(licenses);
        String saved = prefs.get("boundaryLicense", "OFF");
        switch (saved.toUpperCase()) {
            case "TECHNICIAN": licenseCombo.setSelectedItem("Technician"); break;
            case "GENERAL": licenseCombo.setSelectedItem("General"); break;
            case "EXTRA": licenseCombo.setSelectedItem("Extra"); break;
            default: licenseCombo.setSelectedItem("Off");
        }
        row1.add(licenseCombo);
        panel.add(row1);

        // Row 2: Enable toggles
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JCheckBox enableDigital = new JCheckBox("Enable Digital Boundary");
        JCheckBox enableVoice = new JCheckBox("Enable Voice Boundary");
        enableDigital.setSelected(prefs.getBoolean("boundaryEnableDigital", false));
        enableVoice.setSelected(prefs.getBoolean("boundaryEnableVoice", false));
        row2.add(enableDigital);
        row2.add(enableVoice);
        panel.add(row2);

        // Row 3: Viewer selector
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row3.add(new JLabel("View:"));
        String[] views = new String[]{"Digital Ranges", "Voice Ranges", "Full Spectrum"};
        JComboBox<String> viewCombo = new JComboBox<>(views);
        row3.add(viewCombo);
        panel.add(row3);

        // Ranges area (exposes boundaries)
        JTextArea rangesArea = new JTextArea();
        rangesArea.setEditable(false);
        rangesArea.setLineWrap(true);
        rangesArea.setWrapStyleWord(true);
        JScrollPane rangesScroll = new JScrollPane(rangesArea);
        rangesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        rangesScroll.setBorder(BorderFactory.createTitledBorder("Allowed Ranges"));
        rangesScroll.setPreferredSize(new Dimension(520, 220));

        // Helper: populate ranges according to license + view
        java.util.function.BiConsumer<String, String> updateRanges = (licenseSel, viewSel) -> {
            org.example.BandPlan.License lic = org.example.BandPlan.License.OFF;
            if ("Technician".equals(licenseSel)) lic = org.example.BandPlan.License.TECHNICIAN;
            else if ("General".equals(licenseSel)) lic = org.example.BandPlan.License.GENERAL;
            else if ("Extra".equals(licenseSel)) lic = org.example.BandPlan.License.EXTRA;

            String summary;
            if ("Voice Ranges".equals(viewSel)) {
                summary = org.example.BandPlan.allowedRangesSummary(lic, org.example.BandPlan.ModeCategory.VOICE);
            } else if ("Full Spectrum".equals(viewSel)) {
                summary = org.example.BandPlan.fullSpectrumSummary(lic);
            } else {
                summary = org.example.BandPlan.allowedRangesSummary(lic, org.example.BandPlan.ModeCategory.DIGITAL);
            }
            rangesArea.setText(summary);
            rangesArea.setCaretPosition(0);
        };

        // Listeners
        licenseCombo.addActionListener(e -> {
            String sel = (String) licenseCombo.getSelectedItem();
            String val = "OFF";
            if ("Technician".equals(sel)) val = "TECHNICIAN";
            else if ("General".equals(sel)) val = "GENERAL";
            else if ("Extra".equals(sel)) val = "EXTRA";
            prefs.put("boundaryLicense", val);
            updateRanges.accept(sel, (String) viewCombo.getSelectedItem());
        });

        enableDigital.addActionListener(e -> prefs.putBoolean("boundaryEnableDigital", enableDigital.isSelected()));
        enableVoice.addActionListener(e -> prefs.putBoolean("boundaryEnableVoice", enableVoice.isSelected()));
        viewCombo.addActionListener(e -> updateRanges.accept((String) licenseCombo.getSelectedItem(), (String) viewCombo.getSelectedItem()));

        // Initial fill of ranges based on saved value
        updateRanges.accept((String) licenseCombo.getSelectedItem(), (String) viewCombo.getSelectedItem());
        panel.add(Box.createVerticalStrut(8));
        panel.add(rangesScroll);

        JTextArea help = new JTextArea();
        help.setEditable(false);
        help.setOpaque(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setText("Enforcement defaults OFF. Digital boundary restricts data modes; Voice boundary restricts phone/voice modes.\n" +
                "60m uses the 5 FCC channels (±1.5 kHz). 30m has no phone. VHF/UHF allocations are broadly allowed for all classes.\n" +
                "Region: US by default. Future versions can add region profiles and advisory ARRL subband overlays.");
        help.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(10));
        panel.add(help);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createSafetyPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel info = new JLabel("Safety limits and interlocks will be configured here.");
        info.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(info);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createCatServerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
        CatServerManager mgr = CatServerManager.getInstance();

        JLabel title = new JLabel("CAT IP Server");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        // Autostart checkbox (default ON)
        boolean autoDefault = mgr.isAutostartEnabled();
        JCheckBox autoCb = new JCheckBox("Run CAT server (auto-start on launch)", autoDefault);
        autoCb.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(autoCb);
        autoCb.addActionListener(e -> {
            boolean on = autoCb.isSelected();
            mgr.setAutostartEnabled(on);
            if (on) {
                try {
                    mgr.start();
                } catch (Exception ex) {
                    LogBuffer.error("Settings → CAT Server: Autostart start() failed", ex);
                    JOptionPane.showMessageDialog(this,
                            "Failed to start CAT server: " + ex.getMessage(),
                            "CAT Server Error",
                            JOptionPane.ERROR_MESSAGE);
                    autoCb.setSelected(false);
                    mgr.setAutostartEnabled(false);
                }
            } else {
                mgr.stop();
            }
        });

        panel.add(Box.createVerticalStrut(8));

        // Port row
        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        portRow.add(new JLabel("Port:"));
        JTextField portField = new JTextField(6);
        portField.setText(String.valueOf(mgr.getSavedPort()));
        portRow.add(portField);
        JButton applyPort = new JButton("Apply Port");
        portRow.add(applyPort);
        JLabel portHint = new JLabel("Default 60000. Changing port may require restart.");
        portHint.setForeground(Color.GRAY);
        portHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(portRow);
        panel.add(portHint);

        panel.add(Box.createVerticalStrut(8));

        // Status row
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JLabel statusLbl = new JLabel("Status: " + (mgr.isRunning() ? "Running" : "Stopped"));
        JLabel clientsLbl = new JLabel("Clients: 0");
        JButton startBtn = new JButton("Start");
        JButton stopBtn = new JButton("Stop");
        startBtn.setEnabled(!mgr.isRunning());
        stopBtn.setEnabled(mgr.isRunning());
        statusRow.add(statusLbl);
        statusRow.add(Box.createHorizontalStrut(12));
        statusRow.add(clientsLbl);
        statusRow.add(Box.createHorizontalStrut(12));
        statusRow.add(startBtn);
        statusRow.add(stopBtn);
        panel.add(statusRow);

        // Wire listeners to reflect live server status
        mgr.setStatusListener(running -> SwingUtilities.invokeLater(() -> {
            statusLbl.setText("Status: " + (running ? "Running" : "Stopped"));
            startBtn.setEnabled(!running);
            stopBtn.setEnabled(running);
        }));
        mgr.setClientCountListener(cnt -> SwingUtilities.invokeLater(() -> clientsLbl.setText("Clients: " + cnt)));

        // Actions
        applyPort.addActionListener(e -> {
            try {
                int p = Integer.parseInt(portField.getText().trim());
                if (p < 1024 || p > 65535) throw new NumberFormatException();
                mgr.savePort(p);
                JOptionPane.showMessageDialog(this,
                        "Port saved to " + p + (mgr.isRunning() ? "\nRestart server to apply." : ""),
                        "CAT Server",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (NumberFormatException ex) {
                LogBuffer.warn("Settings → CAT Server: Invalid port entered: '" + portField.getText() + "'");
                JOptionPane.showMessageDialog(this,
                        "Please enter a valid port between 1024 and 65535.",
                        "Invalid Port",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        startBtn.addActionListener(e -> {
            try {
                mgr.start();
            } catch (Exception ex) {
                LogBuffer.error("Settings → CAT Server: Start button start() failed", ex);
                JOptionPane.showMessageDialog(this,
                        "Failed to start CAT server: " + ex.getMessage(),
                        "CAT Server Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        stopBtn.addActionListener(e -> mgr.stop());

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JComponent createFooterBar() {
        JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        footer.add(close);
        return footer;
    }

    private JPanel createHelpPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JLabel title = new JLabel("Help & Diagnostics");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        JLabel desc = new JLabel("Use these tools to collect recent logs (~last 1 minute) for troubleshooting.");
        desc.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(desc);
        panel.add(Box.createVerticalStrut(12));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton dumpBtn = new JButton("Dump Log File…");
        JButton openFolderBtn = new JButton("Open Logs Folder");
        btnRow.add(dumpBtn);
        btnRow.add(openFolderBtn);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(btnRow);

        dumpBtn.addActionListener(e -> {
            try {
                java.io.File f = LogBuffer.get().dumpToDefaultLocation("manual");
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Log dumped to:\n" + f.getAbsolutePath(),
                        "Log Dumped",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                LogBuffer.error("Settings → Help tab: Dump Log File action failed", ex);
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Failed to dump log: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        openFolderBtn.addActionListener(e -> {
            try {
                java.io.File dir = LogBuffer.getDefaultLogsDir();
                if (dir.exists()) {
                    if (java.awt.Desktop.isDesktopSupported()) {
                        java.awt.Desktop.getDesktop().open(dir);
                    } else {
                        JOptionPane.showMessageDialog(SettingsDialog.this,
                                "Desktop integration not supported. Folder: \n" + dir.getAbsolutePath(),
                                "Logs Folder",
                                JOptionPane.INFORMATION_MESSAGE);
                    }
                } else {
                    JOptionPane.showMessageDialog(SettingsDialog.this,
                            "Logs folder does not exist yet. It will be created on first dump.",
                            "Logs Folder",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Failed to open logs folder: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    private JPanel createDisplayPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);

        JLabel title = new JLabel("Display & UI Scaling");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(8));

        JCheckBox autoScale = new JCheckBox("Auto-scale based on screen vs design resolution");
        autoScale.setAlignmentX(Component.LEFT_ALIGNMENT);
        autoScale.setSelected(prefs.getBoolean(UIScaler.KEY_AUTO, true));
        panel.add(autoScale);

        // Design-time resolution row
        JPanel designRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        designRow.add(new JLabel("Design resolution:"));
        JTextField wField = new JTextField(5);
        JTextField hField = new JTextField(5);
        wField.setText(String.valueOf(prefs.getInt(UIScaler.KEY_DESIGN_W, UIScaler.DEFAULT_DESIGN_W)));
        hField.setText(String.valueOf(prefs.getInt(UIScaler.KEY_DESIGN_H, UIScaler.DEFAULT_DESIGN_H)));
        designRow.add(new JLabel("W:"));
        designRow.add(wField);
        designRow.add(Box.createHorizontalStrut(6));
        designRow.add(new JLabel("H:"));
        designRow.add(hField);
        panel.add(Box.createVerticalStrut(6));
        panel.add(designRow);

        // Manual percent row
        JPanel manualRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        manualRow.add(new JLabel("Manual scale:"));
        JSlider percent = new JSlider(75, 200, prefs.getInt(UIScaler.KEY_MANUAL_PERCENT, UIScaler.DEFAULT_MANUAL_PERCENT));
        percent.setMajorTickSpacing(25);
        percent.setMinorTickSpacing(5);
        percent.setPaintTicks(true);
        percent.setPaintLabels(true);
        manualRow.add(percent);
        JLabel percentLbl = new JLabel(percent.getValue() + "%");
        manualRow.add(percentLbl);
        panel.add(Box.createVerticalStrut(6));
        panel.add(manualRow);

        // Computed factor preview
        JLabel preview = new JLabel();
        preview.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(6));
        panel.add(preview);

        // Buttons
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton applyBtn = new JButton("Apply Now");
        JButton saveBtn = new JButton("Save");
        btns.add(applyBtn);
        btns.add(saveBtn);
        JLabel hint = new JLabel("Applying may re-layout UI; some sizes update on reopen.");
        hint.setForeground(Color.GRAY);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(Box.createVerticalStrut(8));
        panel.add(btns);
        panel.add(hint);

        // Enable/disable fields based on auto checkbox
        java.util.function.Consumer<Boolean> toggle = on -> {
            wField.setEnabled(on);
            hField.setEnabled(on);
            percent.setEnabled(!on);
        };
        toggle.accept(autoScale.isSelected());
        autoScale.addActionListener(e -> toggle.accept(autoScale.isSelected()));

        // Live label updates
        percent.addChangeListener(e -> percentLbl.setText(percent.getValue() + "%"));

        Runnable updatePreview = () -> {
            try {
                boolean auto = autoScale.isSelected();
                int dw = Integer.parseInt(wField.getText().trim());
                int dh = Integer.parseInt(hField.getText().trim());
                int mp = percent.getValue();
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                float scale;
                if (auto) {
                    float sx = screen.width / (float) Math.max(1, dw);
                    float sy = screen.height / (float) Math.max(1, dh);
                    scale = Math.min(sx, sy);
                } else {
                    scale = mp / 100f;
                }
                scale = Math.max(UIScaler.MIN_SCALE, Math.min(UIScaler.MAX_SCALE, scale));
                preview.setText(String.format("Preview scale: %.2fx  (Screen %dx%d vs %dx%d)", scale, screen.width, screen.height, dw, dh));
            } catch (Exception ex) {
                preview.setText("Preview scale: -");
            }
        };
        updatePreview.run();
        wField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
        });
        hField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updatePreview.run(); }
        });
        percent.addChangeListener(e -> updatePreview.run());
        autoScale.addActionListener(e -> updatePreview.run());

        // Apply now
        applyBtn.addActionListener(e -> {
            try {
                boolean auto = autoScale.isSelected();
                int dw = Integer.parseInt(wField.getText().trim());
                int dh = Integer.parseInt(hField.getText().trim());
                int mp = percent.getValue();
                prefs.putBoolean(UIScaler.KEY_AUTO, auto);
                prefs.putInt(UIScaler.KEY_DESIGN_W, Math.max(640, Math.min(10000, dw)));
                prefs.putInt(UIScaler.KEY_DESIGN_H, Math.max(480, Math.min(10000, dh)));
                prefs.putInt(UIScaler.KEY_MANUAL_PERCENT, Math.max(75, Math.min(200, mp)));
                // Re-apply to this window's owner tree
                java.awt.Window owner = SwingUtilities.getWindowAncestor(SettingsDialog.this);
                if (owner == null) owner = SettingsDialog.this.getOwner();
                UIScaler.applyAndUpdateWindowTree(owner);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Invalid values. Please check width/height numbers.",
                        "Invalid Input",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        // Save only
        saveBtn.addActionListener(e -> {
            try {
                boolean auto = autoScale.isSelected();
                int dw = Integer.parseInt(wField.getText().trim());
                int dh = Integer.parseInt(hField.getText().trim());
                int mp = percent.getValue();
                prefs.putBoolean(UIScaler.KEY_AUTO, auto);
                prefs.putInt(UIScaler.KEY_DESIGN_W, Math.max(640, Math.min(10000, dw)));
                prefs.putInt(UIScaler.KEY_DESIGN_H, Math.max(480, Math.min(10000, dh)));
                prefs.putInt(UIScaler.KEY_MANUAL_PERCENT, Math.max(75, Math.min(200, mp)));
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Saved. Changes apply on reopen or via 'Apply Now'.",
                        "Saved",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(SettingsDialog.this,
                        "Invalid values. Please check width/height numbers.",
                        "Invalid Input",
                        JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(Box.createVerticalGlue());
        return panel;
    }
}