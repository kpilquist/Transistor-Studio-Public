package org.example;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.lang.management.ManagementFactory;

/**
 * Compact footer with: status text (center), small FPS, Volume bar, CPU % + bar, Memory used/total + bar.
 */
public class FooterStatusPanel extends JPanel {
    private final JLabel statusLabel;
    private final JLabel fpsLabel;
    private final JProgressBar volumeBar;
    private final JLabel cpuTextLabel;
    private final JProgressBar cpuBar;
    private final JLabel memTextLabel;
    private final JProgressBar memBar;

    private final javax.swing.Timer sysTimer;

    public FooterStatusPanel() {
        super(new BorderLayout(8, 0));
        setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
        setBackground(UIManager.getColor("Panel.background"));

        // Center status text
        statusLabel = new JLabel("Ready", SwingConstants.CENTER);
        statusLabel.setBorder(new EmptyBorder(6, 6, 6, 6));
        add(statusLabel, BorderLayout.CENTER);

        // Right side metrics container
        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new FlowLayout(FlowLayout.RIGHT, 10, 4));

        // FPS (small)
        fpsLabel = new JLabel("FPS: --");
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = fpsLabel.getFont();
        fpsLabel.setFont(base.deriveFont(Math.max(10f, base.getSize2D() - 2f))); // slightly smaller
        fpsLabel.setBorder(new EmptyBorder(2, 6, 2, 6));
        right.add(fpsLabel);

        // Volume
        right.add(new JLabel("VOL:"));
        volumeBar = new JProgressBar(0, 100);
        volumeBar.setPreferredSize(new Dimension(110, 14));
        volumeBar.setStringPainted(false);
        right.add(volumeBar);

        // CPU
        right.add(new JLabel("CPU:"));
        cpuTextLabel = new JLabel("--% ");
        right.add(cpuTextLabel);
        cpuBar = new JProgressBar(0, 100);
        cpuBar.setPreferredSize(new Dimension(110, 14));
        cpuBar.setStringPainted(false);
        right.add(cpuBar);

        // Memory
        right.add(new JLabel("MEM:"));
        memTextLabel = new JLabel("-- / --");
        right.add(memTextLabel);
        memBar = new JProgressBar(0, 100);
        memBar.setPreferredSize(new Dimension(140, 14));
        memBar.setStringPainted(false);
        right.add(memBar);

        add(right, BorderLayout.EAST);

        // Periodic system updates (CPU, Memory)
        sysTimer = new javax.swing.Timer(800, e -> updateSystemStats());
        sysTimer.start();
    }

    public void dispose() {
        try { sysTimer.stop(); } catch (Throwable ignore) {}
    }

    public void setStatusText(String text) {
        statusLabel.setText(text);
    }

    public void setFps(String text, boolean capped) {
        fpsLabel.setText(text);
        if (capped) {
            fpsLabel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Color.RED, 2, true),
                    new EmptyBorder(0, 4, 0, 4)));
        } else {
            fpsLabel.setBorder(new EmptyBorder(2, 6, 2, 6));
        }
    }

    public void setVolumePercent(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        volumeBar.setValue(p);
        volumeBar.setToolTipText(p + "%");
    }

    private void updateSystemStats() {
        // CPU
        int cpuPercent = readCpuPercent();
        cpuTextLabel.setText(cpuPercent + "% ");
        cpuBar.setValue(cpuPercent);
        // Memory
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        memTextLabel.setText(humanBytes(used) + " / " + humanBytes(total));
        int memPct = total > 0 ? (int) Math.round((used * 100.0) / total) : 0;
        memBar.setValue(memPct);
    }

    private static String humanBytes(long bytes) {
        double b = bytes;
        String[] units = {"B","KB","MB","GB","TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) { b /= 1024; u++; }
        return String.format("%.1f %s", b, units[u]);
    }

    private static int readCpuPercent() {
        try {
            java.lang.management.OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            // Try com.sun.management.OperatingSystemMXBean for getSystemCpuLoad
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double load = ((com.sun.management.OperatingSystemMXBean) osBean).getSystemCpuLoad();
                if (load >= 0.0) return (int) Math.round(load * 100.0);
            }
        } catch (Throwable ignore) {}
        // Fallback: use available processors heuristic (not accurate), return --
        return 0;
    }
}
