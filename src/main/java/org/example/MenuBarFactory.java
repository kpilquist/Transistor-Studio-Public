package org.example;

import javax.swing.*;
import java.awt.event.ActionListener;

public class MenuBarFactory {

    public static JMenuBar createMenuBar(DeviceListPanel deviceListPanel) {
        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenu themeMenu = new JMenu("Theme");
        JMenu helpMenu = new JMenu("Help");

        // Help menu items
        JMenuItem dumpLogItem = new JMenuItem("Dump Log File…");
        dumpLogItem.addActionListener(e -> {
            try {
                JFileChooser chooser = new JFileChooser(LogBuffer.getDefaultLogsDir());
                chooser.setDialogTitle("Dump Log File");
                chooser.setSelectedFile(new java.io.File(LogBuffer.getDefaultLogsDir(),
                        new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + "_manual.log"));
                int res = chooser.showSaveDialog(menuBar);
                if (res == JFileChooser.APPROVE_OPTION) {
                    java.io.File file = chooser.getSelectedFile();
                    LogBuffer.get().dumpToFile(file);
                    JOptionPane.showMessageDialog(menuBar,
                            "Log dumped to:\n" + file.getAbsolutePath(),
                            "Log Dumped",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(menuBar,
                        "Failed to dump log: " + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
        helpMenu.add(dumpLogItem);

        JMenuItem aboutItem = new JMenuItem("About…");
        aboutItem.addActionListener(e -> AboutDialog.show(menuBar, deviceListPanel));
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        // File menu items

        // Settings item
        JMenuItem settingsItem = new JMenuItem("Settings...");
        settingsItem.addActionListener(e -> {
            java.awt.Window window = SwingUtilities.getWindowAncestor(menuBar);
            java.awt.Frame owner = (window instanceof java.awt.Frame) ? (java.awt.Frame) window : null;
            SettingsDialog dialog = new SettingsDialog(owner);
            dialog.setVisible(true);
        });
        fileMenu.add(settingsItem);

        fileMenu.addSeparator();

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));
        fileMenu.add(exitItem);

        // Theme menu items
        ButtonGroup themeGroup = new ButtonGroup();
        addThemeItem(themeMenu, themeGroup, "System", ThemeManager.Theme.SYSTEM);
        addThemeItem(themeMenu, themeGroup, "Red", ThemeManager.Theme.RED);
        addThemeItem(themeMenu, themeGroup, "Blue", ThemeManager.Theme.BLUE);
        addThemeItem(themeMenu, themeGroup, "Black", ThemeManager.Theme.BLACK);

        // Set selected based on saved theme
        ThemeManager.Theme saved = ThemeManager.getSavedTheme();
        for (int i = 0; i < themeMenu.getItemCount(); i++) {
            JMenuItem item = themeMenu.getItem(i);
            if (item instanceof JRadioButtonMenuItem) {
                JRadioButtonMenuItem radio = (JRadioButtonMenuItem) item;
                if (radio.getText().equalsIgnoreCase(saved.name())) {
                    radio.setSelected(true);
                }
                if (saved == ThemeManager.Theme.SYSTEM && radio.getText().equals("System")) {
                    radio.setSelected(true);
                }
                if (saved == ThemeManager.Theme.RED && radio.getText().equals("Red")) {
                    radio.setSelected(true);
                }
                if (saved == ThemeManager.Theme.BLUE && radio.getText().equals("Blue")) {
                    radio.setSelected(true);
                }
                if (saved == ThemeManager.Theme.BLACK && radio.getText().equals("Black")) {
                    radio.setSelected(true);
                }
            }
        }

        menuBar.add(fileMenu);
        menuBar.add(themeMenu);
        menuBar.add(helpMenu);

        return menuBar;
    }

    private static void addThemeItem(JMenu themeMenu, ButtonGroup group, String label, ThemeManager.Theme theme) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(label);
        group.add(item);
        item.addActionListener(e -> ThemeManager.applyTheme(theme));
        themeMenu.add(item);
    }

}
