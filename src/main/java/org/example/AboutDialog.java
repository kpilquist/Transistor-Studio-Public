package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * Simple About dialog that shows app + environment info and allows copying
 * the details to the clipboard for issue reporting.
 */
public final class AboutDialog {
    private AboutDialog() {}

    public static void show(Component parent, DeviceListPanel deviceListPanel) {
        java.awt.Window owner = (parent instanceof java.awt.Window) ? (java.awt.Window) parent : SwingUtilities.getWindowAncestor(parent);

        String version = safe(() -> AppInfo.VERSION, "unknown");
        String osName = safe(() -> System.getProperty("os.name"), "");
        String osVer  = safe(() -> System.getProperty("os.version"), "");
        String osArch = safe(() -> System.getProperty("os.arch"), "");
        String javaVer = safe(() -> System.getProperty("java.version"), "");

        String inId  = deviceListPanel != null ? nullToNA(getFromDevicePanel(deviceListPanel,
                "getSelectedInputIdentityString", "getSelectedInputDevice")) : "N/A";
        String outId = deviceListPanel != null ? nullToNA(getFromDevicePanel(deviceListPanel,
                "getSelectedOutputIdentityString", "getSelectedOutputDevice")) : "N/A";
        String serId = deviceListPanel != null ? nullToNA(getFromDevicePanel(deviceListPanel,
                "getSelectedSerialPortIdentityString", "getSelectedSerialPortName")) : "N/A";
        String pttId = deviceListPanel != null ? nullToNA(getFromDevicePanel(deviceListPanel,
                "getSelectedPttPortIdentityString", "getSelectedPttPortName")) : "N/A";

        StringBuilder sb = new StringBuilder();
        sb.append("Transistor Studio\n");
        sb.append("Version: ").append(version).append('\n');
        sb.append("OS: ").append(osName).append(" ").append(osVer).append(" (" ).append(osArch).append(")\n");
        sb.append("Java: ").append(javaVer).append('\n');
        sb.append('\n');
        sb.append("Audio Input: ").append(inId).append("\n");
        sb.append("Audio Output: ").append(outId).append("\n");
        sb.append("Serial (CAT): ").append(serId).append("\n");
        sb.append("PTT Port: ").append(pttId).append("\n");

        String details = sb.toString();

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JLabel title = new JLabel("Transistor Studio — About");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1f));
        panel.add(title, BorderLayout.NORTH);

        JTextArea ta = new JTextArea(details, 10, 50);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));
        panel.add(new JScrollPane(ta), BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton copyBtn = new JButton("Copy to Clipboard");
        JButton closeBtn = new JButton("Close");
        buttons.add(copyBtn);
        buttons.add(closeBtn);
        panel.add(buttons, BorderLayout.SOUTH);

        final String titleStr = "About Transistor Studio";
        JDialog dialog;
        if (owner instanceof Frame) {
            dialog = new JDialog((Frame) owner, titleStr, true);
        } else if (owner instanceof Dialog) {
            dialog = new JDialog((Dialog) owner, titleStr, true);
        } else {
            dialog = new JDialog((Frame) null, titleStr, true);
        }
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);

        copyBtn.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details), null);
                JOptionPane.showMessageDialog(dialog, "Details copied to clipboard.", "Copied", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog, "Failed to copy: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
        closeBtn.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
    }

    private static String nullToNA(String s) {
        return (s == null || s.isEmpty()) ? "N/A" : s;
    }

    private interface SupplierX<T> { T get() throws Exception; }
    private static <T> T safe(SupplierX<T> s, T def) {
        try { return s.get(); } catch (Throwable t) { return def; }
    }

    private static String getFromDevicePanel(DeviceListPanel dp, String primaryMethod, String fallbackMethod) {
        if (dp == null) return "";
        // Try primary via reflection
        String v = invokeGetter(dp, primaryMethod);
        if (v != null && !v.isEmpty()) return v;
        // Try fallback
        v = invokeGetter(dp, fallbackMethod);
        return v == null ? "" : v;
    }

    private static String invokeGetter(DeviceListPanel dp, String methodName) {
        try {
            if (methodName == null || methodName.isEmpty()) return null;
            java.lang.reflect.Method m = dp.getClass().getMethod(methodName);
            Object r = m.invoke(dp);
            return r == null ? null : String.valueOf(r);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
