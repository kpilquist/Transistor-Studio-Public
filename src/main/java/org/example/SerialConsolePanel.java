package org.example;

import javax.swing.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class SerialConsolePanel extends JPanel {
    private final JTextArea textArea;
    private final JCheckBox autoScrollCb;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    // Persistence
    private final File logFile;
    private static final long MAX_LOG_BYTES = 2_000_000; // ~2 MB cap to avoid unbounded growth

    public SerialConsolePanel() {
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createTitledBorder("Serial Communication Log"));

        textArea = new JTextArea();
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setBackground(Color.BLACK);
        textArea.setForeground(Color.GREEN);

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton clearBtn = new JButton("Clear");
        autoScrollCb = new JCheckBox("Auto-scroll", true);

        toolbar.add(clearBtn);
        toolbar.add(autoScrollCb);

        add(toolbar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);

        // Default height for the console
        setPreferredSize(new Dimension(400, 150));

        // Prepare log file under user data directory
        this.logFile = resolveDefaultLogFile();
        loadFromDisk();

        // Wire clear to also wipe file on disk
        clearBtn.addActionListener(e -> {
            textArea.setText("");
            try { if (logFile != null && logFile.exists()) new FileOutputStream(logFile, false).close(); } catch (IOException ignore) {}
        });
    }

    public void log(String direction, String message) {
        String timestamp = timeFormat.format(new Date());
        String cleanMessage = message != null ? message.trim() : "null";
        String line = String.format("[%s] %s: %s%n", timestamp, direction, cleanMessage);

        // Update UI on EDT
        SwingUtilities.invokeLater(() -> {
            textArea.append(line);
            if (autoScrollCb.isSelected()) {
                textArea.setCaretPosition(textArea.getDocument().getLength());
            }
        });

        // Persist to disk off the EDT
        appendToDisk(line);
    }

    private File resolveDefaultLogFile() {
        try {
            String userHome = System.getProperty("user.home");
            File baseDir;
            // Prefer AppData/Roaming on Windows if available
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                baseDir = new File(appData, "TransistorStudio");
            } else {
                baseDir = new File(userHome, ".transistor-studio");
            }
            if (!baseDir.exists()) baseDir.mkdirs();
            return new File(baseDir, "serial-console.log");
        } catch (Throwable t) {
            return null;
        }
    }

    private void loadFromDisk() {
        if (logFile == null || !logFile.exists()) return;
        try {
            // If file is too large, read only the tail ~1 MB
            long len = logFile.length();
            long toReadFrom = Math.max(0, len - Math.min(len, MAX_LOG_BYTES));
            try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                raf.seek(toReadFrom);
                byte[] buf = new byte[(int)(len - toReadFrom)];
                raf.readFully(buf);
                String text = new String(buf, StandardCharsets.UTF_8);
                // If we started mid-line, trim to first newline
                int nl = text.indexOf('\n');
                if (toReadFrom > 0 && nl >= 0 && nl + 1 < text.length()) {
                    text = text.substring(nl + 1);
                }
                final String finalText = text;
                SwingUtilities.invokeLater(() -> {
                    textArea.append(finalText);
                    if (autoScrollCb.isSelected()) {
                        textArea.setCaretPosition(textArea.getDocument().getLength());
                    }
                });
            }
        } catch (IOException ignored) {
        }
    }

    private synchronized void appendToDisk(String line) {
        if (logFile == null) return;
        try {
            // Cap size by truncating the file if it grows too big
            if (logFile.exists() && logFile.length() > MAX_LOG_BYTES) {
                // keep last 1 MB
                long keep = MAX_LOG_BYTES / 2; // shrink to half when trimming
                long len = logFile.length();
                long start = Math.max(0, len - keep);
                try (RandomAccessFile raf = new RandomAccessFile(logFile, "r")) {
                    raf.seek(start);
                    byte[] buf = new byte[(int)(len - start)];
                    raf.readFully(buf);
                    int nl = 0;
                    while (nl < buf.length && buf[nl] != '\n') nl++; // align to line
                    try (FileOutputStream fos = new FileOutputStream(logFile, false)) {
                        fos.write(buf, Math.min(nl + 1, buf.length), buf.length - Math.min(nl + 1, buf.length));
                    }
                }
            }
            try (OutputStream os = new FileOutputStream(logFile, true)) {
                os.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }
}

