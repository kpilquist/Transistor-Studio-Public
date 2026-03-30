package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

public class ChatBoxPanel extends JPanel {
    private enum SortMode { DEFAULT, OLDEST, NEWEST }

    // Batch append buffers per sub-box to avoid per-character HTML reflow/flicker
    private final Map<String, StringBuilder> pendingText = new LinkedHashMap<>();
    private javax.swing.Timer flushTimer;

    private static class ChatSubPanel extends JPanel {
        final String key; // marker/decoder id
        String modeName;
        Color markerColor;
        final long createdAt;
        long lastUpdatedAt;

        final JTextPane output;
        final JLabel titleLabel;
        final JPanel colorDot;
        final JButton optionsButton;

        ChatSubPanel(String key, String modeName, Color markerColor) {
            super(new BorderLayout(4, 4));
            this.key = key;
            this.modeName = modeName;
            this.markerColor = markerColor != null ? markerColor : new Color(200, 200, 200);
            this.createdAt = System.currentTimeMillis();
            this.lastUpdatedAt = this.createdAt;

            // Top mini toolbar
            JToolBar tb = new JToolBar();
            tb.setFloatable(false);
            tb.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));

            colorDot = new JPanel();
            colorDot.setPreferredSize(new Dimension(12, 12));
            colorDot.setMaximumSize(new Dimension(12, 12));
            colorDot.setBackground(this.markerColor);
            colorDot.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

            titleLabel = new JLabel(buildTitleText());

            optionsButton = new JButton("⋮");
            optionsButton.setFocusable(false);
            optionsButton.setMargin(new Insets(1,6,1,6));
            JPopupMenu menu = new JPopupMenu();
            JMenuItem clearItem = new JMenuItem("Clear");
            clearItem.addActionListener(e -> setHtmlBody(""));
            menu.add(clearItem);
            optionsButton.addActionListener(e -> menu.show(optionsButton, 0, optionsButton.getHeight()));

            tb.add(colorDot);
            tb.add(Box.createHorizontalStrut(6));
            tb.add(titleLabel);
            tb.add(Box.createHorizontalGlue());
            tb.add(optionsButton);

            // Output area
            output = new JTextPane() {
                @Override public boolean getScrollableTracksViewportWidth() { return true; }
            };
            output.setEditable(false);
            output.setContentType("text/html");
            String initialHtml = "<html><head><style>body{font-family:sans-serif; font-variant: normal; text-transform: none; white-space: pre-wrap; word-wrap:break-word; word-break:break-word; margin:4px; text-align:left; width:100%; max-width:none;} .line{display:inline;}</style></head><body></body></html>";
            output.setText(initialHtml);

            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220,220,220)),
                    BorderFactory.createEmptyBorder(2,2,2,2)));

            add(tb, BorderLayout.NORTH);
            
            // Allow this panel to stretch vertically if needed by setting a minimum preferred size based on layout manager
            JScrollPane scroll = new JScrollPane(output);
            scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
            add(scroll, BorderLayout.CENTER);
        }

        private String buildTitleText() {
            String mode = modeName != null ? modeName : "?";
            return String.format("%s — [%s]", mode, key != null ? key : "?");
        }

        void updateMeta(String modeName, Color color) {
            if (modeName != null) this.modeName = modeName;
            if (color != null) {
                this.markerColor = color;
                colorDot.setBackground(color);
            }
            titleLabel.setText(buildTitleText());
        }

        void appendHtml(String html) {
            try {
                javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) output.getDocument();
                javax.swing.text.Element[] roots = doc.getRootElements();
                javax.swing.text.Element htmlEl = roots[0];
                javax.swing.text.Element body = null;
                for (int i = 0; i < htmlEl.getElementCount(); i++) {
                    javax.swing.text.Element child = htmlEl.getElement(i);
                    Object name = child.getAttributes().getAttribute(javax.swing.text.StyleConstants.NameAttribute);
                    if (name == javax.swing.text.html.HTML.Tag.BODY) { body = child; break; }
                }
                if (body == null) body = htmlEl;
                doc.insertBeforeEnd(body, html);
                output.setCaretPosition(doc.getLength());
                lastUpdatedAt = System.currentTimeMillis();
            } catch (Exception ex) {
                LogBuffer.error("ChatBoxPanel.appendHtml failed to insert HTML", ex);
            }
        }

        void setHtmlBody(String html) {
            try {
                javax.swing.text.html.HTMLDocument doc = (javax.swing.text.html.HTMLDocument) output.getDocument();
                javax.swing.text.Element[] roots = doc.getRootElements();
                javax.swing.text.Element htmlEl = roots[0];
                javax.swing.text.Element body = null;
                for (int i = 0; i < htmlEl.getElementCount(); i++) {
                    javax.swing.text.Element child = htmlEl.getElement(i);
                    Object name = child.getAttributes().getAttribute(javax.swing.text.StyleConstants.NameAttribute);
                    if (name == javax.swing.text.html.HTML.Tag.BODY) { body = child; break; }
                }
                if (body == null) body = htmlEl;
                // Replace body content
                doc.setInnerHTML((javax.swing.text.Element) body, html);
                output.setCaretPosition(doc.getLength());
                lastUpdatedAt = System.currentTimeMillis();
            } catch (Exception ex) {
                LogBuffer.error("ChatBoxPanel.setHtmlBody failed to set inner HTML", ex);
            }
        }
    }

    // Pinned Active TX viewer mirrors currently selected marker's sub-box
    private class ActiveTxView extends JPanel {
        private final JPanel colorDot;
        private final JLabel titleLabel;
        private final JTextPane viewPane;

        ActiveTxView() {
            super(new BorderLayout(4,4));
            setBorder(BorderFactory.createTitledBorder("Active TX"));

            JToolBar tb = new JToolBar();
            tb.setFloatable(false);
            tb.setBorder(BorderFactory.createEmptyBorder(2,2,2,2));

            colorDot = new JPanel();
            colorDot.setPreferredSize(new Dimension(12, 12));
            colorDot.setMaximumSize(new Dimension(12, 12));
            colorDot.setBackground(new Color(200,200,200));
            colorDot.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));

            titleLabel = new JLabel("No active marker");

            tb.add(colorDot);
            tb.add(Box.createHorizontalStrut(6));
            tb.add(titleLabel);
            tb.add(Box.createHorizontalGlue());

            viewPane = new JTextPane() {
                @Override public boolean getScrollableTracksViewportWidth() { return true; }
            };
            viewPane.setEditable(false);
            viewPane.setContentType("text/html");
            String initialHtml = "<html><head><style>body{font-family:sans-serif; font-variant: normal; text-transform: none; white-space: pre-wrap; word-wrap:break-word; word-break:break-word; margin:4px; text-align:left; width:100%; max-width:none;} .line{display:inline;}</style></head><body><i>Select a TX marker to view its chat here.</i></body></html>";
            viewPane.setText(initialHtml);

            add(tb, BorderLayout.NORTH);
            add(new JScrollPane(viewPane), BorderLayout.CENTER);
            
            // Provide a preferred size so it doesn't get squeezed too much if the top list is long
            setPreferredSize(new Dimension(200, 150));
        }

        void updateHeader(String id, String mode, Color color) {
            if (color != null) colorDot.setBackground(color);
            String m = mode != null ? mode : "?";
            String i = id != null ? id : "?";
            titleLabel.setText(String.format("%s — [%s]", m, i));
        }

        void bindTo(ChatSubPanel sub, String id, String mode) {
            if (sub != null) {
                viewPane.setDocument(sub.output.getDocument());
                updateHeader(id, mode, sub.markerColor);
            }
        }

        void showPlaceholder() {
            String html = "<html><head><style>body{font-family:sans-serif;margin:4px;}</style></head><body><i>No active TX marker.</i></body></html>";
            viewPane.setText(html);
            titleLabel.setText("No active marker");
            colorDot.setBackground(new Color(200,200,200));
        }
    }

    private static final String DIVIDER_LOCATION_KEY = "chatBoxDividerLocation";
    private static final String PREF_SEND_ON_RETURN = "chatSendOnReturn";

    private final Preferences prefs;
    private JTextPane outputChat; // points to default sub-box's output for backward-compat
    private JTextField inputChat;

    // Multi-output containers
    private final Map<String, ChatSubPanel> subBoxes = new LinkedHashMap<>(); // preserves creation order
    private JPanel boxesPanel; // holds ChatSubPanels vertically
    private SortMode sortMode = SortMode.DEFAULT;

    // Cache of current selections by id (to obtain color/mode for Active TX view)
    private final Map<String, SpectrogramSelection> selectionById = new LinkedHashMap<>();

    // New controls
    private JComboBox<String> markerCombo;
    private JButton startButton;
    private JButton pauseButton;
    private JButton sendButton;
    private JButton identifyButton; // Added Identify button
    private JCheckBox sendOnReturnCheck;

    // Active TX pinned view at the bottom
    private ActiveTxView activeTxView;
    private String activeTxId;

    // Listeners
    private final List<Consumer<String>> inputListeners = new ArrayList<>();
    private Consumer<String> markerChangeListener;
    private Runnable startClickListener; // for modes like PSK/RTTY (toggle start)
    private Runnable pauseClickListener;
    private Runnable startPressListener;   // for CW (press-and-hold)
    private Runnable startReleaseListener; // for CW (press-and-hold)
    private Runnable stopClickListener;    // Stop all TX instantly
    private Runnable identifyClickListener; // Added Identify listener

    private static final String DEFAULT_BOX_KEY = "DEFAULT";

    public ChatBoxPanel() {
        prefs = Preferences.userNodeForPackage(ChatBoxPanel.class);
        setLayout(new BorderLayout());

        // Multi chat boxes container using GridLayout to divide space equally among children
        // We'll dynamically adjust the grid layout rows based on component count
        boxesPanel = new JPanel(new GridLayout(0, 1, 0, 4));
        boxesPanel.setBorder(BorderFactory.createEmptyBorder(4,4,4,4));

        // Create default sub-box for backward compatibility
        ChatSubPanel defaultBox = ensureSubPanel("DEFAULT", "Chat", new Color(220,220,220));
        outputChat = defaultBox.output; // keep reference for old APIs

        // Top toolbar above boxes: sort selector
        JToolBar topToolbar = new JToolBar();
        topToolbar.setFloatable(false);
        topToolbar.setBorder(BorderFactory.createTitledBorder("Decoder Outputs"));
        topToolbar.add(new JLabel("Sort:"));
        JComboBox<String> sortCombo = new JComboBox<>(new String[]{"Default (Created)", "Oldest Updated", "Newest Updated"});
        sortCombo.addActionListener(e -> {
            int idx = sortCombo.getSelectedIndex();
            sortMode = idx == 1 ? SortMode.OLDEST : idx == 2 ? SortMode.NEWEST : SortMode.DEFAULT;
            resortBoxes();
        });
        topToolbar.add(Box.createHorizontalStrut(8));
        topToolbar.add(sortCombo);
        
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(topToolbar, BorderLayout.NORTH);
        
        // Wrap boxesPanel in a panel that forces its children to take available space
        // Using a scroll pane on the outside catches overflow if they don't fit
        JScrollPane outputScrollPane = new JScrollPane(boxesPanel);
        outputScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        // Important to allow the items to stretch vertically within the scroll pane viewport
        outputScrollPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                triggerScrollUpdate();
            }
        });

        topContainer.add(outputScrollPane, BorderLayout.CENTER);

        // Bottom input/control panel
        JPanel controlsPanel = new JPanel(new BorderLayout(6, 6));

        // Row 1: TX controls
        JPanel txPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        txPanel.setBorder(BorderFactory.createTitledBorder("TX Controls"));
        txPanel.add(new JLabel("Active Marker:"));
        markerCombo = new JComboBox<>();
        markerCombo.setPrototypeDisplayValue("[A] 1500.0 Hz");
        markerCombo.addActionListener(e -> {
            String sel = (String) markerCombo.getSelectedItem();
            if (sel != null) {
                // item format "[ID] freq Hz" -> extract ID inside brackets
                int i1 = sel.indexOf('[');
                int i2 = sel.indexOf(']');
                if (i1 != -1 && i2 > i1) {
                    String id = sel.substring(i1 + 1, i2);
                    // Update pinned Active TX view
                    setActiveTxMarker(id);
                    // Notify external listener
                    if (markerChangeListener != null) {
                        markerChangeListener.accept(id);
                    }
                }
            }
        });
        txPanel.add(markerCombo);

        startButton = new JButton("Start");
        pauseButton = new JButton("Pause");
        JButton stopButton = new JButton("Stop");
        identifyButton = new JButton("Identify"); // Added Identify button

        txPanel.add(startButton);
        txPanel.add(pauseButton);
        txPanel.add(stopButton);
        txPanel.add(identifyButton); // Added Identify button
        
        // Initially, TX is inactive; disable Pause until Start is engaged
        pauseButton.setEnabled(false);

        // Fire click for PSK/RTTY
        startButton.addActionListener(e -> { if (startClickListener != null) startClickListener.run(); });
        pauseButton.addActionListener(e -> { if (pauseClickListener != null) pauseClickListener.run(); });
        // Stop all TX
        stopButton.addActionListener(e -> { if (stopClickListener != null) stopClickListener.run(); });
        // Identify button action
        identifyButton.addActionListener(e -> { if (identifyClickListener != null) identifyClickListener.run(); });
        // Press-and-hold for CW
        startButton.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { if (startPressListener != null) startPressListener.run(); }
            @Override public void mouseReleased(MouseEvent e) { if (startReleaseListener != null) startReleaseListener.run(); }
        });

        controlsPanel.add(txPanel, BorderLayout.NORTH);

        // Row 2: Input row with send controls
        JPanel inputRow = new JPanel(new BorderLayout(6, 6));
        inputChat = new JTextField();
        inputChat.setBorder(BorderFactory.createTitledBorder("Chat Input"));
        inputRow.add(inputChat, BorderLayout.CENTER);

        JPanel sendControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        sendButton = new JButton("Send");
        sendControls.add(sendButton);
        sendOnReturnCheck = new JCheckBox("Send on Enter");
        boolean sendOnReturn = prefs.getBoolean(PREF_SEND_ON_RETURN, true);
        sendOnReturnCheck.setSelected(sendOnReturn);
        sendControls.add(sendOnReturnCheck);
        inputRow.add(sendControls, BorderLayout.EAST);

        // Wire send behavior
        Action sendAction = new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                String text = inputChat.getText();
                if (text != null && !text.isEmpty()) {
                    for (Consumer<String> l : inputListeners) l.accept(text);
                    inputChat.setText("");
                }
            }
        };
        sendButton.addActionListener(sendAction);
        // Conditionally bind Enter key
        bindEnterToSend(sendOnReturn);
        sendOnReturnCheck.addActionListener(e -> {
            boolean enabled = sendOnReturnCheck.isSelected();
            prefs.putBoolean(PREF_SEND_ON_RETURN, enabled);
            bindEnterToSend(enabled);
        });

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topContainer, controlsPanel);
        splitPane.setResizeWeight(0.8);
        splitPane.setDividerSize(5);

        // Add pinned Active TX view above input row
        activeTxView = new ActiveTxView();
        controlsPanel.add(activeTxView, BorderLayout.CENTER);

        // Place input row at the very bottom
        controlsPanel.add(inputRow, BorderLayout.SOUTH);

        int savedDividerLocation = prefs.getInt(DIVIDER_LOCATION_KEY, 200);
        splitPane.setDividerLocation(savedDividerLocation);
        // Persist divider movement immediately when the user drags it
        splitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> {
            try { prefs.putInt(DIVIDER_LOCATION_KEY, splitPane.getDividerLocation()); } catch (Throwable ignore) {}
        });
        // Also guard on resize/layout changes to keep the saved value fresh
        splitPane.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                try { prefs.putInt(DIVIDER_LOCATION_KEY, splitPane.getDividerLocation()); } catch (Throwable ignore) {}
            }
        });

        add(splitPane, BorderLayout.CENTER);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { prefs.putInt(DIVIDER_LOCATION_KEY, splitPane.getDividerLocation()); } catch (Throwable ignore) {}
            try { prefs.putBoolean(PREF_SEND_ON_RETURN, sendOnReturnCheck.isSelected()); } catch (Throwable ignore) {}
        }));
    }

    private void bindEnterToSend(boolean enable) {
        // Clear existing Enter bindings
        InputMap im = inputChat.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputChat.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), null);
        if (enable) {
            am.put("send", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    String text = inputChat.getText();
                    if (text != null && !text.isEmpty()) {
                        for (Consumer<String> l : inputListeners) l.accept(text);
                        inputChat.setText("");
                    }
                }
            });
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        }
    }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private ChatSubPanel ensureSubPanel(String key, String modeName, Color color) {
        if (key == null || key.isEmpty()) key = DEFAULT_BOX_KEY;
        ChatSubPanel p = subBoxes.get(key);
        if (p == null) {
            p = new ChatSubPanel(key, modeName, color);
            subBoxes.put(key, p);
            boxesPanel.add(p);
            // Re-evaluate scroll sizing
            triggerScrollUpdate();
        } else {
            p.updateMeta(modeName, color);
        }
        boxesPanel.revalidate();
        boxesPanel.repaint();
        return p;
    }

    private void resortBoxes() {
        // Remove all components and re-add in the desired order
        boxesPanel.removeAll();
        java.util.List<ChatSubPanel> list = new ArrayList<>(subBoxes.values());
        if (sortMode == SortMode.OLDEST) {
            list.sort(Comparator.comparingLong(a -> a.lastUpdatedAt));
        } else if (sortMode == SortMode.NEWEST) {
            list.sort((a, b) -> Long.compare(b.lastUpdatedAt, a.lastUpdatedAt));
        } // DEFAULT keeps insertion order from LinkedHashMap
        for (ChatSubPanel p : list) {
            boxesPanel.add(p);
        }
        triggerScrollUpdate();
        boxesPanel.revalidate();
        boxesPanel.repaint();
    }
    
    private void triggerScrollUpdate() {
        Container parent = boxesPanel.getParent();
        if (parent instanceof JViewport) {
            int boxCount = boxesPanel.getComponentCount();
            if (boxCount > 0) {
                int parentHeight = parent.getHeight();
                // Try to make each box take 25% of the total available viewport height
                int desiredBoxHeight = Math.max(100, parentHeight / 4); // min 100px per box
                int totalHeight = desiredBoxHeight * boxCount + (boxCount - 1) * ((GridLayout)boxesPanel.getLayout()).getVgap();
                
                // Only force preferred size if it exceeds viewport, else let layout stretch them
                if (totalHeight > parentHeight) {
                    boxesPanel.setPreferredSize(new Dimension(boxesPanel.getWidth(), totalHeight));
                } else {
                    boxesPanel.setPreferredSize(null); // let it fill
                }
                boxesPanel.revalidate();
            }
        }
    }

    private void ensureFlushTimer() {
        if (flushTimer == null) {
            flushTimer = new javax.swing.Timer(50, e -> flushPending());
        }
        if (!flushTimer.isRunning()) flushTimer.start();
    }

    private void flushPending() {
        if (pendingText.isEmpty()) {
            if (flushTimer != null) flushTimer.stop();
            return;
        }
        boolean anyAppended = false;
        // Copy keys to avoid concurrent modification if ensureSubPanel gets called elsewhere
        java.util.List<String> keys = new java.util.ArrayList<>(pendingText.keySet());
        for (String k : keys) {
            StringBuilder buf = pendingText.get(k);
            if (buf == null || buf.length() == 0) continue;
            ChatSubPanel panel = subBoxes.get(k);
            if (panel == null) continue;
            String s = buf.toString();
            buf.setLength(0);
            String escaped = escapeHtml(s);
            escaped = escaped.replace("\r\n", "\n");
            escaped = escaped.replace("\r", "\n");
            escaped = escaped.replaceAll("\n{3,}", "\n\n");
            escaped = escaped.replace("\n", "<br/>");
            panel.appendHtml(escaped);
            anyAppended = true;
        }
        if (anyAppended) {
            resortBoxes();
        }
        // Stop timer if nothing left to flush
        boolean hasPending = false;
        for (StringBuilder b : pendingText.values()) {
            if (b != null && b.length() > 0) { hasPending = true; break; }
        }
        if (!hasPending && flushTimer != null) flushTimer.stop();
    }

    public void appendText(String text) {
        String targetKey = (activeTxId != null && !activeTxId.isEmpty()) ? activeTxId : DEFAULT_BOX_KEY;
        appendTextTo(targetKey, text, null, null);
    }

    public void appendTextTo(String key, String text, String modeName, Color color) {
        final String k = (key == null || key.isEmpty()) ? DEFAULT_BOX_KEY : key;
        if (text == null) return;
        SwingUtilities.invokeLater(() -> {
            // Ensure panel exists/metadata updated
            ensureSubPanel(k, modeName, color);
            String s = text;
            // If looks like explicit HTML snippet, append immediately without buffering
            String trimmed = s.stripLeading();
            boolean looksHtml = trimmed.startsWith("<") && (trimmed.contains("</") || trimmed.contains("/>") || trimmed.contains("span") || trimmed.contains("b") || trimmed.contains("br") || trimmed.contains("div") || trimmed.contains("p"));
            if (looksHtml) {
                ChatSubPanel panel = subBoxes.get(k);
                if (panel != null) {
                    panel.appendHtml(s);
                    resortBoxes();
                }
                return;
            }
            // Accumulate plain text into per-box buffer to avoid per-character layout churn
            StringBuilder buf = pendingText.computeIfAbsent(k, kk -> new StringBuilder());
            buf.append(s);
            ensureFlushTimer();
        });
    }

    public void addInputListener(Consumer<String> listener) {
        inputListeners.add(listener);
    }

    // Append pre-formatted HTML directly to a sub-box
    public void appendHtmlTo(String key, String html, String modeName, Color color) {
        final String k = key;
        final String h = html;
        SwingUtilities.invokeLater(() -> {
            ChatSubPanel panel = ensureSubPanel(k, modeName, color);
            panel.appendHtml(h);
            resortBoxes();
        });
    }

    // Update a sub-box header (mode text and marker color) without appending content
    public void updateSubBoxMeta(String key, String modeName, Color color) {
        final String k = key;
        SwingUtilities.invokeLater(() -> {
            ChatSubPanel panel = ensureSubPanel(k, modeName, color);
            // ensureSubPanel already updates meta if panel exists
            boxesPanel.revalidate();
            boxesPanel.repaint();
        });
    }

    // Clear contents of a specific sub-box
    public void clearSubBox(String key) {
        final String k = key;
        SwingUtilities.invokeLater(() -> {
            ChatSubPanel panel = subBoxes.get(k);
            if (panel != null) panel.setHtmlBody("");
        });
    }

    // UI update helpers
    public void setMarkers(List<SpectrogramSelection> selections) {
        SwingUtilities.invokeLater(() -> {
            // 1) Update combo and cache
            markerCombo.removeAllItems();
            selectionById.clear();

            java.util.HashSet<String> presentIds = new java.util.HashSet<>();

            if (selections == null || selections.isEmpty()) {
                markerCombo.addItem("<no markers>");
                markerCombo.setEnabled(false);
                // Remove all non-default chat sub-boxes since all markers are gone
                java.util.List<String> toRemove = new java.util.ArrayList<>(subBoxes.keySet());
                for (String k : toRemove) {
                    if (!DEFAULT_BOX_KEY.equals(k)) {
                        subBoxes.remove(k);
                    }
                }
                resortBoxes();
                if (activeTxView != null) activeTxView.showPlaceholder();
                activeTxId = null;
                return;
            }
            markerCombo.setEnabled(true);
            for (SpectrogramSelection s : selections) {
                String sid = s.getId();
                if (sid != null) {
                    selectionById.put(sid, s);
                    presentIds.add(sid);
                }
                String id = sid != null ? sid : "?";
                double center = s.getStartFrequency() + s.getBandwidth() / 2.0;
                String mode = s.getModeName() != null ? s.getModeName() : "-";
                markerCombo.addItem(String.format("[%s] %.1f Hz (%s)", id, center, mode));
                // Ensure each present marker has a visible chat sub-box immediately (even before first decode)
                ensureSubPanel(id, s.getModeName(), s.getColor());
            }

            // 2) Reconcile chat sub-boxes: remove any that are no longer present
            java.util.List<String> keys = new java.util.ArrayList<>(subBoxes.keySet());
            for (String k : keys) {
                if (DEFAULT_BOX_KEY.equals(k)) continue;
                if (!presentIds.contains(k)) {
                    subBoxes.remove(k);
                }
            }
            resortBoxes();

            // 3) Active TX binding: if still present, rebind; otherwise clear
            if (activeTxId != null && selectionById.containsKey(activeTxId)) {
                updateActiveTxBinding(activeTxId);
            } else {
                activeTxId = null;
                if (activeTxView != null) activeTxView.showPlaceholder();
            }
        });
    }

    public void setMarkerChangeListener(Consumer<String> l) { this.markerChangeListener = l; }
    public void setStartClickListener(Runnable r) { this.startClickListener = r; }
    public void setPauseClickListener(Runnable r) { this.pauseClickListener = r; }
    public void setStartPressListener(Runnable r) { this.startPressListener = r; }
    public void setStartReleaseListener(Runnable r) { this.startReleaseListener = r; }
    public void setStopClickListener(Runnable r) { this.stopClickListener = r; }
    public void setIdentifyClickListener(Runnable r) { this.identifyClickListener = r; }

    // Remove a marker's chat sub-box (e.g., when marker is removed from waterfall)
    public void removeMarker(String id) {
        if (id == null || id.isEmpty() || DEFAULT_BOX_KEY.equals(id)) return;
        SwingUtilities.invokeLater(() -> {
            ChatSubPanel removed = subBoxes.remove(id);
            if (removed != null) {
                boxesPanel.remove(removed);
                resortBoxes();
                if (id.equals(activeTxId)) {
                    activeTxId = null;
                    if (activeTxView != null) activeTxView.showPlaceholder();
                }
            }
        });
    }

    // Public API: set or change the currently active TX marker id
    public void setActiveTxMarker(String id) {
        SwingUtilities.invokeLater(() -> {
            activeTxId = id;
            updateActiveTxBinding(id);
        });
    }
    
    // Allows setting the combo box selection programmatically
    public void setActiveMarkerId(String id) {
        if (id == null) return;
        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < markerCombo.getItemCount(); i++) {
                String item = markerCombo.getItemAt(i);
                if (item != null && item.startsWith("[" + id + "]")) {
                    markerCombo.setSelectedIndex(i);
                    break;
                }
            }
        });
    }

    // Bind the Active TX view to the sub-box of the given id
    private void updateActiveTxBinding(String id) {
        if (activeTxView == null) return;
        if (id == null || id.isEmpty()) {
            activeTxView.showPlaceholder();
            return;
        }
        SpectrogramSelection sel = selectionById.get(id);
        String mode = sel != null ? sel.getModeName() : null;
        Color color = sel != null ? sel.getColor() : null;
        // Ensure subpanel exists so the document is stable and shared
        ChatSubPanel sub = ensureSubPanel(id, mode, color);
        activeTxView.bindTo(sub, id, mode);
    }

    // Expose simple UI state updates for TX Start button
    public void setTxActive(boolean active) {
        SwingUtilities.invokeLater(() -> {
            if (startButton != null) {
                startButton.setText(active ? "Stop" : "Start");
            }
            if (pauseButton != null) {
                pauseButton.setEnabled(active);
            }
        });
    }
}
