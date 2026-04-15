package org.example;

import javax.swing.*;
import java.awt.*;
import java.util.prefs.Preferences;

public class PSKReporterPanel extends JPanel {
    private final Preferences prefs = Preferences.userNodeForPackage(PSKReporterPanel.class);

    private static final String PREF_ENABLED = "pskr.enabled";
    private static final String PREF_HOST = "pskr.host";
    private static final String PREF_PORT = "pskr.port";
    private static final String PREF_MY_CALL = "pskr.myCall";
    private static final String PREF_MY_LOC = "pskr.myLoc";
    private static final String PREF_ANT = "pskr.antenna";
    private static final String PREF_RIG = "pskr.rig";
    private static final String PREF_PERSISTENT_ID = "pskr.pid";
    private static final String PREF_TEST = "pskr.test";

    private JCheckBox enableCb;
    private JTextField hostField;
    private JSpinner portSpinner;
    private JTextField myCallField;
    private JTextField myLocField;
    private JTextField antennaField;
    private JTextField rigField;
    private JTextField persistentIdField;
    private JCheckBox testCb;

    public PSKReporterPanel() {
        setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4,4,4,4);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 0;

        enableCb = new JCheckBox("Enable PSK Reporter Upload");
        enableCb.setSelected(prefs.getBoolean(PREF_ENABLED, false));
        enableCb.addActionListener(e -> prefs.putBoolean(PREF_ENABLED, enableCb.isSelected()));
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        add(enableCb, gbc);

        gbc.gridwidth = 1;
        gbc.gridy++;
        add(new JLabel("Collector Host:"), gbc);
        hostField = new JTextField(prefs.get(PREF_HOST, "report.pskreporter.info"));
        hostField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(hostField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("Collector Port:"), gbc);
        portSpinner = new JSpinner(new SpinnerNumberModel(prefs.getInt(PREF_PORT, 4739), 1, 65535, 1));
        portSpinner.addChangeListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(portSpinner, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("My Callsign (receiver):"), gbc);
        myCallField = new JTextField(prefs.get(PREF_MY_CALL, ""));
        myCallField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(myCallField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("My Locator (Maidenhead):"), gbc);
        myLocField = new JTextField(prefs.get(PREF_MY_LOC, ""));
        myLocField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(myLocField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("Antenna Info:"), gbc);
        antennaField = new JTextField(prefs.get(PREF_ANT, ""));
        antennaField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(antennaField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("Rig Info:"), gbc);
        rigField = new JTextField(prefs.get(PREF_RIG, ""));
        rigField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(rigField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        add(new JLabel("Persistent ID:"), gbc);
        persistentIdField = new JTextField(prefs.get(PREF_PERSISTENT_ID, ""));
        persistentIdField.addActionListener(e -> save());
        gbc.gridx = 1; gbc.weightx = 1.0;
        add(persistentIdField, gbc);

        gbc.gridx = 0; gbc.gridy++; gbc.weightx = 0;
        testCb = new JCheckBox("Mark as Test records (0x80)");
        testCb.setSelected(prefs.getBoolean(PREF_TEST, false));
        testCb.addActionListener(e -> prefs.putBoolean(PREF_TEST, testCb.isSelected()));
        gbc.gridwidth = 2;
        add(testCb, gbc);

        JButton saveBtn = new JButton("Save");
        saveBtn.addActionListener(e -> save());
        gbc.gridy++; gbc.gridx = 0; gbc.gridwidth = 2;
        add(saveBtn, gbc);
    }

    private void save() {
        prefs.put(PREF_HOST, hostField.getText().trim());
        prefs.putInt(PREF_PORT, (Integer) portSpinner.getValue());
        prefs.put(PREF_MY_CALL, myCallField.getText().trim());
        prefs.put(PREF_MY_LOC, myLocField.getText().trim());
        prefs.put(PREF_ANT, antennaField.getText().trim());
        prefs.put(PREF_RIG, rigField.getText().trim());
        prefs.put(PREF_PERSISTENT_ID, persistentIdField.getText().trim());
    }

    public boolean isEnabled() { return enableCb.isSelected(); }
    public String getHost() { return hostField.getText().trim(); }
    public int getPort() { return (Integer) portSpinner.getValue(); }
    public String getMyCallsign() { return myCallField.getText().trim(); }
    public String getMyLocator() { return myLocField.getText().trim(); }
    public String getAntennaInfo() { return antennaField.getText().trim(); }
    public String getRigInfo() { return rigField.getText().trim(); }
    public String getPersistentId() { return persistentIdField.getText().trim(); }
    public boolean isTest() { return testCb.isSelected(); }
}
