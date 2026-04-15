package org.example;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Simple contact logging panel for amateur radio QSOs.
 *
 * Features:
 * - Form with common fields (Call, RST S/R, Freq, Mode, Date, Time, Name, QTH, Grid, Power, Band, Serial, Notes)
 * - Auto Fill: populates Frequency, Mode and current time/date
 * - Add/Save to a session table and Export CSV
 */
public class ContactLogPanel extends JPanel {
    private final JCheckBox autoFillCheck;
    private final JButton fillNowBtn;
    private final JButton addBtn;
    private final JButton clearFormBtn;
    private final JButton exportCsvBtn;

    // Last detected callsign from decoders (for convenience)
    private volatile String contextDetectedCallsign = null;

    // Form fields
    private final JTextField callField = new JTextField();
    private final JTextField rstSentField = new JTextField("59");
    private final JTextField rstRcvdField = new JTextField("59");
    private final JTextField freqField = new JTextField(); // MHz
    private final JTextField modeField = new JTextField(); // Rig mode (SSB/AM/FM/etc.)
    private final JTextField digitalModeField = new JTextField(); // Active digital mode (PSK31/RTTY/etc.)
    private final JTextField dateField = new JTextField(); // YYYY-MM-DD
    private final JTextField timeField = new JTextField(); // HH:mm:ss (UTC)
    private final JTextField nameField = new JTextField();
    private final JTextField qthField = new JTextField();
    private final JTextField gridField = new JTextField();
    private final JTextField powerField = new JTextField(); // Watts
    private final JTextField bandField = new JTextField();
    private final JTextField serialField = new JTextField();
    private final JTextArea notesArea = new JTextArea(3, 20);

    private final DefaultTableModel tableModel;
    private final JTable table;

    // Last known context
    private volatile Long contextFreqHz = null;
    private volatile String contextMode = null; // rig mode
    private volatile String contextDigitalMode = null; // digital mode

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * Update the detected callsign context and pre-fill the Call field if it's empty.
     */
    public void setDetectedCallsign(String call) {
        if (call == null) return;
        SwingUtilities.invokeLater(() -> {
            contextDetectedCallsign = call;
            if (callField.getText() == null || callField.getText().trim().isEmpty()) {
                callField.setText(call);
            }
        });
    }

    public ContactLogPanel() {
        super(new BorderLayout());

        // Controls row
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        autoFillCheck = new JCheckBox("Auto Fill freq/mode/time", true);
        fillNowBtn = new JButton("Fill Now");
        addBtn = new JButton("Add to Log");
        clearFormBtn = new JButton("Clear Form");
        exportCsvBtn = new JButton("Export CSV…");
        top.add(autoFillCheck);
        top.add(fillNowBtn);
        top.add(addBtn);
        top.add(clearFormBtn);
        top.add(exportCsvBtn);
        add(top, BorderLayout.NORTH);

        // Form grid
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridy = 0;

        int col = 0;
        addLabeled(form, gbc, col++, "Call", callField, 1);
        addLabeled(form, gbc, col++, "RST S", rstSentField, 1);
        addLabeled(form, gbc, col++, "RST R", rstRcvdField, 1);
        addLabeled(form, gbc, col++, "Freq (MHz)", freqField, 1);
        addLabeled(form, gbc, col++, "Mode", modeField, 1);
        addLabeled(form, gbc, col++, "Digital Mode", digitalModeField, 1);

        gbc.gridy++;
        col = 0;
        addLabeled(form, gbc, col++, "Date (UTC)", dateField, 1);
        addLabeled(form, gbc, col++, "Time (UTC)", timeField, 1);
        addLabeled(form, gbc, col++, "Name", nameField, 1);
        addLabeled(form, gbc, col++, "QTH", qthField, 1);
        addLabeled(form, gbc, col++, "Grid", gridField, 1);

        gbc.gridy++;
        col = 0;
        addLabeled(form, gbc, col++, "Power (W)", powerField, 1);
        addLabeled(form, gbc, col++, "Band", bandField, 1);
        addLabeled(form, gbc, col++, "Serial", serialField, 1);

        gbc.gridy++;
        gbc.gridx = 0; gbc.gridwidth = 5;
        form.add(new JLabel("Notes"), gbc);
        gbc.gridy++;
        gbc.weightx = 1.0; gbc.weighty = 0.0;
        JScrollPane noteScroll = new JScrollPane(notesArea);
        form.add(noteScroll, gbc);

        add(form, BorderLayout.CENTER);

        // Table
        String[] cols = new String[]{"Date(UTC)", "Time(UTC)", "Call", "RST S", "RST R", "Freq(MHz)", "Mode", "Digital Mode", "Band", "Name", "QTH", "Grid", "Power", "Serial", "Notes"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
        table = new JTable(tableModel);
        table.setAutoCreateRowSorter(true);
        add(new JScrollPane(table), BorderLayout.SOUTH);

        // Wiring
        fillNowBtn.addActionListener(this::onFillNow);
        addBtn.addActionListener(this::onAdd);
        clearFormBtn.addActionListener(e -> clearForm());
        exportCsvBtn.addActionListener(e -> exportCsv());

        // Initial autofill
        autoFillNow();
    }

    private void addLabeled(JPanel panel, GridBagConstraints gbc, int gridx, String label, JComponent comp, int gridwidth) {
        gbc.gridx = gridx; gbc.gridwidth = gridwidth; gbc.weightx = 0.0;
        panel.add(new JLabel(label), gbc);
        gbc.gridy++; gbc.gridx = gridx; gbc.gridwidth = gridwidth; gbc.weightx = 1.0;
        panel.add(comp, gbc);
        gbc.gridy--; // restore for next column
    }

    private void onFillNow(ActionEvent e) {
        autoFillNow();
    }

    private void onAdd(ActionEvent e) {
        String date = dateField.getText().trim();
        String time = timeField.getText().trim();
        String call = callField.getText().trim().toUpperCase(Locale.ROOT);
        String rstS = rstSentField.getText().trim();
        String rstR = rstRcvdField.getText().trim();
        String freq = freqField.getText().trim();
        String mode = modeField.getText().trim();
        String dmode = digitalModeField.getText().trim();
        String band = bandField.getText().trim();
        String name = nameField.getText().trim();
        String qth = qthField.getText().trim();
        String grid = gridField.getText().trim();
        String pwr = powerField.getText().trim();
        String serial = serialField.getText().trim();
        String notes = notesArea.getText().trim();

        tableModel.addRow(new Object[]{date, time, call, rstS, rstR, freq, mode, dmode, band, name, qth, grid, pwr, serial, notes});
        clearForm();
        if (autoFillCheck.isSelected()) autoFillNow();
    }

    public void clearForm() {
        callField.setText("");
        rstSentField.setText("59");
        rstRcvdField.setText("59");
        // freq/mode/time remain (often reused)
        nameField.setText("");
        qthField.setText("");
        gridField.setText("");
        powerField.setText("");
        serialField.setText("");
        notesArea.setText("");
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("log.csv"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try (BufferedWriter w = new BufferedWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
                // header
                for (int c = 0; c < tableModel.getColumnCount(); c++) {
                    if (c > 0) w.write(',');
                    w.write(escapeCsv(tableModel.getColumnName(c)));
                }
                w.write("\n");
                // rows
                for (int r = 0; r < tableModel.getRowCount(); r++) {
                    for (int c = 0; c < tableModel.getColumnCount(); c++) {
                        if (c > 0) w.write(',');
                        Object val = tableModel.getValueAt(r, c);
                        w.write(escapeCsv(val == null ? "" : val.toString()));
                    }
                    w.write("\n");
                }
                w.flush();
                JOptionPane.showMessageDialog(this, "Exported to " + f.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to export: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ===== Auto-fill context API =====

    public void setContext(Long freqHz, String modeText) {
        contextFreqHz = freqHz;
        contextMode = modeText;
        if (autoFillCheck.isSelected()) autoFillNow();
    }

    public void setContextFrequency(Long freqHz) {
        contextFreqHz = freqHz;
        if (autoFillCheck.isSelected()) autoFillNow();
    }

    public void setContextMode(String modeText) {
        contextMode = modeText;
        if (autoFillCheck.isSelected()) autoFillNow();
    }

    public void setContextDigitalMode(String modeText) {
        contextDigitalMode = modeText;
        if (autoFillCheck.isSelected()) autoFillNow();
    }

    public void autoFillNow() {
        // time/date UTC now
        ZonedDateTime z = ZonedDateTime.ofInstant(Instant.now(), ZoneId.of("UTC"));
        dateField.setText(DATE_FMT.format(z));
        timeField.setText(TIME_FMT.format(z));

        // freq
        if (contextFreqHz != null && contextFreqHz > 0) {
            double mhz = contextFreqHz / 1_000_000.0;
            freqField.setText(String.format(Locale.US, "%.4f", mhz));
            bandField.setText(guessBandMeters(contextFreqHz));
        }

        // rig mode
        if (contextMode != null && !contextMode.isEmpty()) {
            modeField.setText(contextMode);
        }
        // digital mode
        if (contextDigitalMode != null && !contextDigitalMode.isEmpty()) {
            digitalModeField.setText(contextDigitalMode);
        }
    }

    private String guessBandMeters(long hz) {
        // Very rough/common amateur HF bands + 2m/70cm
        long khz = hz / 1000;
        if (between(khz, 1800, 2000)) return "160m";
        if (between(khz, 3500, 4000)) return "80m";
        if (between(khz, 5330, 5405)) return "60m";
        if (between(khz, 7000, 7300)) return "40m";
        if (between(khz, 10100, 10150)) return "30m";
        if (between(khz, 14000, 14350)) return "20m";
        if (between(khz, 18068, 18168)) return "17m";
        if (between(khz, 21000, 21450)) return "15m";
        if (between(khz, 24890, 24990)) return "12m";
        if (between(khz, 28000, 29700)) return "10m";
        if (between(khz, 50000, 54000)) return "6m";
        if (between(khz, 144000, 148000)) return "2m";
        if (between(khz, 420000, 450000)) return "70cm";
        return "";
    }

    private boolean between(long v, long a, long b) { return v >= a && v <= b; }
}
