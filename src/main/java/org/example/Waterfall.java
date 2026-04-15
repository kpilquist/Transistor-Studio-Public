package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.function.BiConsumer;
import java.util.Arrays;

public class Waterfall extends JPanel implements SpectrogramDisplay {
    // Listener for start/base frequency changes
    private final java.util.List<java.util.function.Consumer<Long>> startFrequencyListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    // Helper record for CW estimation results
    public static class CwEst {
        public final boolean valid;
        public final double centerAbsHz;
        public final double snrDb;
        public CwEst(boolean valid, double centerAbsHz, double snrDb) {
            this.valid = valid;
            this.centerAbsHz = centerAbsHz;
            this.snrDb = snrDb;
        }
    }
    // AFC (CW) listener: provides measured pitch (Hz, audio) and SNR (dB)
    private BiConsumer<Double, Double> cwPitchListener;
    // AFC center markers disabled by default (removed per user request)
    private boolean cwCenterMarkerEnabled = false;
    private double cwMeasuredCenterHz = -1; // absolute Hz (legacy single-center; keep for compatibility)
    // Per-selection measured centers (absolute Hz) — target values (from estimators/AFC)
    private final Map<String, Double> cwCentersBySelection = new ConcurrentHashMap<>();
    // Tweened display positions for CENTER markers (absolute Hz)
    private final Map<String, Double> cwCenterDisplayBySelection = new ConcurrentHashMap<>();
    // Tween timing state
    private long centerTweenLastNs = 0L;
    // Time constant for exponential easing toward target (milliseconds)
    private volatile double centerTweenTauMs = 180.0; // ~0.18 s feels snappy yet smooth
    // Latest FFT frame snapshot for on-demand per-window estimation
    private volatile double[] lastFftFrame = null;
    // CW target pitch for visualization (audio Hz offset from band start)
    private double cwTargetPitchHz = 700.0;
    // Optional explicit estimator window (absolute Hz). When set, CoG estimator integrates strictly within this range.
    private double cwEstimatorWinLowAbsHz = Double.NaN;
    private double cwEstimatorWinHighAbsHz = Double.NaN;
    // CW ID marker state (absolute frequency in Hz). When enabled, drawn in the ruler and draggable within it.
    private boolean cwIdMarkerEnabled = true;
    private double cwIdMarkerFrequencyHz = -1; // absolute frequency (Hz); -1 means not set
    private boolean cwIdDragging = false;
    private BufferedImage waterfallImage;
    private int[] palette;
    private int width;
    private int height;

    // Ring buffer and rendering state (no per-frame allocations)
    private volatile int ringHead = 0; // index of the top-most logical row in the ring buffer
    private volatile boolean repaintScheduled = false; // to avoid queuing redundant repaints
    private int imgWCache = 0;
    private int imgHCache = 0;
    private int[] pixelBuffer; // DataBufferInt backing array of waterfallImage
    private final ConcurrentLinkedQueue<int[]> lineQueue = new ConcurrentLinkedQueue<>();

    // Render pacing timer (EDT) to decouple drawing from DSP bursts
    private javax.swing.Timer renderPacer;

    // Cached mapping from x pixel to FFT bin index for faster drawing (zoom-aware)
    private int[] xToBinCache = null;
    private int xToBinCacheBins = -1;
    private int xToBinCacheWidth = -1;
    private double xToBinCacheFracStart = -1;
    private double xToBinCacheFracEnd = -1;

    // Ruler properties
    private int rulerHeight = 30;
    private long startFrequency = 0; // Hz
    private int bandwidth; // Hz
    private int inputSampleRate = 48000; // Default
    private String currentTheme = "Spectrum";

    private final List<SpectrogramSelection> selections = new CopyOnWriteArrayList<>();
    private SpectrogramSelection currentSelection = null; // The one being dragged

    // Hover selection state
    private double hoverBandwidth = 0;
    private boolean hoverEnabled = false;
    private java.util.function.Consumer<Double> selectionListener;
    private int mouseX = -1;

    private java.util.function.Consumer<SpectrogramSelection> selectionAddedListener;
    private java.util.function.Consumer<SpectrogramSelection> selectionRemovedListener;
    private java.util.function.Consumer<SpectrogramSelection> activeSelectionChangedListener;
    private java.util.function.Consumer<SpectrogramSelection> selectionChangedListener;

    private boolean digitalPlacementEnabled = true;
    private double radarStartHz = -1;
    private double radarEndHz = -1;

    private static final Color[] SELECTION_COLORS = {
        Color.RED, Color.GREEN, Color.BLUE, Color.CYAN, Color.MAGENTA, Color.ORANGE
    };
    private int selectionCounter = 0;

    // Zoom state
    private Mode currentMode = Mode.SELECTION;
    private double viewStartFreq = 0;
    private double viewEndFreq = 0; // 0 means not set (full bandwidth)
    private final List<java.util.function.Consumer<double[]>> viewChangeListeners = new CopyOnWriteArrayList<>();
    // Zoom drag overlay state
    private boolean zoomDragging = false;
    private int zoomDragStartX = -1;
    private int zoomDragCurrentX = -1;

    // Ruler panning state
    private boolean rulerPanning = false;
    private int rulerPanStartX = -1;
    private double panStartViewStart = 0;
    private double panStartViewEnd = 0;

    // Feature flag: allow ruler shifting (panning/scrolling). Default disabled.
    private boolean rulerShiftingEnabled = false;

    public Waterfall(int width, int height, int bandwidth) {
        this.width = width;
        this.height = height;
        this.bandwidth = bandwidth;
        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);

        // Initialize image buffer (subtract ruler height)
        waterfallImage = new BufferedImage(width, height - rulerHeight, BufferedImage.TYPE_INT_RGB);
        // Cache buffer
        imgWCache = waterfallImage.getWidth();
        imgHCache = waterfallImage.getHeight();
        pixelBuffer = ((DataBufferInt) waterfallImage.getRaster().getDataBuffer()).getData();
        ringHead = 0;
        repaintScheduled = false;

        // Create a color palette (Blue -> Green -> Yellow -> Red)
        createPalette();

        // Ensure CW ID marker is visible immediately at +700 Hz from band start on first paint
        if (cwIdMarkerEnabled && cwIdMarkerFrequencyHz <= 0) {
            cwIdMarkerFrequencyHz = startFrequency + 700;
        }

        // Mouse listeners for selection and ruler interactions
        MouseAdapter mouseHandler = new MouseAdapter() {
            private int startX;

            @Override
            public void mousePressed(MouseEvent e) {
                // CW ID marker interactions in ruler
                if (SwingUtilities.isLeftMouseButton(e) && e.getY() < rulerHeight) {
                    double freq = getFrequencyAt(e.getX());
                    // If marker not set, set to clicked position and start dragging
                    if (!cwIdMarkerEnabled) {
                        // allow setting even if disabled? Respect enabled flag: only interact when enabled
                    }
                    if (cwIdMarkerEnabled) {
                        int markerX = (cwIdMarkerFrequencyHz > 0) ? getXForFrequency(cwIdMarkerFrequencyHz) : -1;
                        if (markerX >= 0 && Math.abs(e.getX() - markerX) <= 10) {
                            cwIdDragging = true;
                            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                            return;
                        } else {
                            // Set marker to clicked position and begin drag
                            cwIdMarkerFrequencyHz = clampToVisible(freq);
                            cwIdDragging = true;
                            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                            repaint();
                            return;
                        }
                    }
                }
                // Start ruler panning if pressed within ruler area
                if (SwingUtilities.isLeftMouseButton(e) && e.getY() < rulerHeight && rulerShiftingEnabled) {
                    rulerPanning = true;
                    rulerPanStartX = e.getX();
                    // establish starting view window (use current zoom if any; otherwise full span)
                    if (viewEndFreq > 0) {
                        panStartViewStart = viewStartFreq;
                        panStartViewEnd = viewEndFreq;
                    } else {
                        panStartViewStart = startFrequency;
                        panStartViewEnd = startFrequency + bandwidth;
                    }
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }

                if (currentMode == Mode.ZOOM && SwingUtilities.isLeftMouseButton(e)) {
                    startX = e.getX();
                    zoomDragging = true;
                    zoomDragStartX = startX;
                    zoomDragCurrentX = startX;
                    repaint();
                    return;
                }

                if (currentMode != Mode.ZOOM && hoverEnabled && SwingUtilities.isLeftMouseButton(e)) {
                    double centerFreq = getFrequencyAt(e.getX());
                    if (selectionListener != null) {
                        selectionListener.accept(centerFreq);
                    }
                    // Add permanent selection
                    SpectrogramSelection sel = new SpectrogramSelection(centerFreq - hoverBandwidth / 2, hoverBandwidth);
                    sel.setLabel("RX"); // Default label
                    // Remove old selections if we only want one active mode?
                    // For now, let's clear old selections to avoid clutter, or maybe keep them?
                    // User said "multipul lanes", so maybe keep them.
                    // But usually you select one active RX.
                    // Let's clear for now to keep it simple, or maybe just add it.
                    // clearSelections();
                    addSelection(sel);
                    repaint();
                    return;
                }

                if (SwingUtilities.isLeftMouseButton(e)) {
                    startX = e.getX();
                    double freq = getFrequencyAt(startX);
                    currentSelection = new SpectrogramSelection(freq, 0);
                    addSelection(currentSelection);
                    repaint();
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    // Remove selection if clicked
                    double freq = getFrequencyAt(e.getX());
                    SpectrogramSelection toRemove = null;
                    for (SpectrogramSelection s : selections) {
                        if (freq >= s.getStartFrequency() && freq <= s.getEndFrequency()) {
                            toRemove = s;
                            break;
                        }
                    }
                    if (toRemove != null) {
                        removeSelection(toRemove);
                        repaint();
                    }
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // Drag CW ID marker horizontally within ruler
                if (cwIdDragging && SwingUtilities.isLeftMouseButton(e)) {
                    double freq = getFrequencyAt(e.getX());
                    cwIdMarkerFrequencyHz = clampToVisible(freq);
                    repaint();
                    return;
                }
                if (rulerPanning && SwingUtilities.isLeftMouseButton(e)) {
                    int dx = e.getX() - rulerPanStartX;
                    double visibleStart = panStartViewStart;
                    double visibleEnd = panStartViewEnd;
                    double visibleWidthHz = Math.max(1, visibleEnd - visibleStart);
                    double hzPerPx = visibleWidthHz / Math.max(1, width);
                    double newStart = visibleStart + dx * hzPerPx;
                    double newEnd = newStart + visibleWidthHz;
                    // Clamp to total band
                    double minStart = startFrequency;
                    double maxStart = startFrequency + bandwidth - visibleWidthHz;
                    if (maxStart < minStart) {
                        maxStart = minStart;
                    }
                    if (newStart < minStart) {
                        newStart = minStart;
                        newEnd = newStart + visibleWidthHz;
                    } else if (newStart > maxStart) {
                        newStart = maxStart;
                        newEnd = newStart + visibleWidthHz;
                    }
                    setViewRange(newStart, newEnd);
                    // keep move cursor
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }

                if (currentMode == Mode.ZOOM && SwingUtilities.isLeftMouseButton(e)) {
                    zoomDragging = true;
                    zoomDragCurrentX = e.getX();
                    // While dragging, show horizontal resize cursor for feedback
                    setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
                    repaint();
                    return;
                }

                if (!hoverEnabled && currentSelection != null) {
                    int currentX = e.getX();
                    double startFreq = getFrequencyAt(startX);
                    double currentFreq = getFrequencyAt(currentX);

                    double minFreq = Math.min(startFreq, currentFreq);
                    double maxFreq = Math.max(startFreq, currentFreq);

                    currentSelection.setStartFrequency(minFreq);
                    currentSelection.setBandwidth(maxFreq - minFreq);
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // Finish CW ID drag
                if (cwIdDragging && SwingUtilities.isLeftMouseButton(e)) {
                    cwIdDragging = false;
                    if (currentMode == Mode.ZOOM) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                    return;
                }
                if (rulerPanning && SwingUtilities.isLeftMouseButton(e)) {
                    rulerPanning = false;
                    rulerPanStartX = -1;
                    // restore cursor based on mode
                    if (currentMode == Mode.ZOOM) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                    return;
                }

                if (currentMode == Mode.ZOOM && SwingUtilities.isLeftMouseButton(e)) {
                    int endX = e.getX();
                    if (Math.abs(endX - startX) > 10) {
                        double f1 = getFrequencyAt(startX);
                        double f2 = getFrequencyAt(endX);
                        setViewRange(Math.min(f1, f2), Math.max(f1, f2));
                    }
                    // end dragging overlay
                    zoomDragging = false;
                    zoomDragStartX = -1;
                    zoomDragCurrentX = -1;
                    // Restore crosshair cursor for zoom mode
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    repaint();
                    return;
                }

                if (!hoverEnabled && currentSelection != null) {
                    // If bandwidth is too small, maybe remove it?
                    if (currentSelection.getBandwidth() < 10) { // 10 Hz min
                        removeSelection(currentSelection);
                    }
                    currentSelection = null;
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                mouseX = e.getX();
                if (hoverEnabled) {
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                mouseX = -1;
                if (hoverEnabled) {
                    repaint();
                }
                // stop ruler panning on exit
                if (rulerPanning) {
                    rulerPanning = false;
                    rulerPanStartX = -1;
                    if (currentMode == Mode.ZOOM) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    } else {
                        setCursor(Cursor.getDefaultCursor());
                    }
                }
                if (currentMode == Mode.ZOOM) {
                    // cancel any pending drag overlay on exit
                    zoomDragging = false;
                    zoomDragStartX = -1;
                    zoomDragCurrentX = -1;
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                    repaint();
                }
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);

        // Mouse wheel for panning over the ruler
        MouseWheelListener wheelHandler = new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.getY() >= 0 && e.getY() < rulerHeight && rulerShiftingEnabled) {
                    // Determine current visible window
                    double visStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
                    double visEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
                    double visWidth = Math.max(1, visEnd - visStart);

                    // Use wheel rotation to pan left/right. Positive rotation usually means scroll down.
                    // Pan by 10% of visible width per notch by default.
                    double step = visWidth * 0.10;
                    int notches = e.getWheelRotation();
                    double delta = notches * step; // to the right for positive

                    double newStart = visStart + delta;
                    double newEnd = visEnd + delta;

                    // Clamp to total available band
                    double minStart = startFrequency;
                    double maxEnd = startFrequency + bandwidth;
                    double maxStart = maxEnd - visWidth;
                    if (maxStart < minStart) maxStart = minStart;

                    if (newStart < minStart) {
                        newStart = minStart;
                        newEnd = newStart + visWidth;
                    } else if (newStart > maxStart) {
                        newStart = maxStart;
                        newEnd = newStart + visWidth;
                    }

                    setViewRange(newStart, newEnd);
                }
            }
        };
        addMouseWheelListener(wheelHandler);

        // Add resize listener
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                handleResize(getWidth(), getHeight());
            }
        });

        // Rendering pacer to decouple drawing cadence from DSP bursts
        int targetFps = 60;
        int frameTimeMs = Math.max(1, 1000 / Math.max(1, targetFps));
        renderPacer = new javax.swing.Timer(frameTimeMs, e -> {
            if (waterfallImage == null || pixelBuffer == null) return;

            // 0) Advance CENTER marker tweening every tick (even when no new lines)
            boolean tweenChanged = advanceCenterTween();

            // 1) Consume queued lines if any
            int linesToPull = 0;
            if (!lineQueue.isEmpty()) {
                linesToPull = 1;
                int qSize = lineQueue.size();
                if (qSize > 3) linesToPull = 2;
                if (qSize > 10) linesToPull = qSize - 2; // Failsafe for massive backups
            }

            final int imgW = imgWCache;
            final int imgH = imgHCache;
            boolean drewAnything = false;

            for (int i = 0; i < linesToPull; i++) {
                int[] queuedLine = lineQueue.poll();
                if (queuedLine == null) break;

                // Write directly into the ring buffer
                ringHead = (ringHead - 1 + imgH) % imgH;
                int destBase = ringHead * imgW;
                System.arraycopy(queuedLine, 0, pixelBuffer, destBase, Math.min(imgW, queuedLine.length));
                drewAnything = true;
            }

            if (drewAnything || tweenChanged) {
                repaint();
            }
        });
        renderPacer.start();
    }

    // Advance tweened CENTER positions toward their targets; return true if any value changed
    private boolean advanceCenterTween() {
        try {
            long now = System.nanoTime();
            if (centerTweenLastNs == 0L) {
                centerTweenLastNs = now;
                // Initialize display map for existing targets to avoid first-jump
                for (Map.Entry<String, Double> e : cwCentersBySelection.entrySet()) {
                    String id = e.getKey();
                    Double tgt = e.getValue();
                    if (id != null && tgt != null && tgt > 0) {
                        cwCenterDisplayBySelection.putIfAbsent(id, tgt);
                    }
                }
                return false;
            }
            double dtMs = (now - centerTweenLastNs) / 1_000_000.0;
            if (dtMs <= 0) return false;
            centerTweenLastNs = now;

            double tau = Math.max(1.0, centerTweenTauMs);
            double alpha = 1.0 - Math.exp(-dtMs / tau);
            if (alpha < 0) alpha = 0;
            if (alpha > 1) alpha = 1;

            boolean changed = false;
            // Move each display toward its target
            for (Map.Entry<String, Double> e : cwCentersBySelection.entrySet()) {
                String id = e.getKey();
                Double target = e.getValue();
                if (id == null || target == null || target <= 0) continue;
                double cur = cwCenterDisplayBySelection.getOrDefault(id, target);
                double next = cur + alpha * (target - cur);
                if (Math.abs(next - cur) > 0.05) { // ~0.05 Hz resolution threshold
                    cwCenterDisplayBySelection.put(id, next);
                    changed = true;
                } else {
                    // Snap when very close to avoid asymptotic tail
                    cwCenterDisplayBySelection.put(id, target);
                }
            }
            // Remove any display entries whose targets were cleared
            if (!cwCenterDisplayBySelection.isEmpty()) {
                java.util.Iterator<String> it = cwCenterDisplayBySelection.keySet().iterator();
                while (it.hasNext()) {
                    String id = it.next();
                    if (!cwCentersBySelection.containsKey(id)) {
                        it.remove();
                        changed = true;
                    }
                }
            }
            return changed;
        } catch (Throwable t) {
            return false;
        }
    }

    // Optional: allow adjusting smoothing from outside
    public void setCenterTweenTimeConstantMs(double ms) {
        this.centerTweenTauMs = Math.max(1.0, ms);
    }

    private void handleResize(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= rulerHeight) return;
        if (newWidth == this.width && newHeight == this.height) return;

        this.width = newWidth;
        this.height = newHeight;

        // Recreate image buffer and caches
        BufferedImage newImage = new BufferedImage(width, height - rulerHeight, BufferedImage.TYPE_INT_RGB);
        waterfallImage = newImage;
        imgWCache = waterfallImage.getWidth();
        imgHCache = waterfallImage.getHeight();
        pixelBuffer = ((DataBufferInt) waterfallImage.getRaster().getDataBuffer()).getData();

        // Invalidate x→bin cache to recompute on next update
        xToBinCache = null;
        xToBinCacheBins = -1;
        xToBinCacheWidth = -1;
        xToBinCacheFracStart = -1;
        xToBinCacheFracEnd = -1;

        // Reset ring buffer head; clear any queued lines
        ringHead = 0;
        lineQueue.clear();
        repaintScheduled = false;

        repaint();
    }

    private double getFrequencyAt(int x) {
        double effectiveStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double effectiveBandwidth = (viewEndFreq > 0) ? (viewEndFreq - viewStartFreq) : bandwidth;
        return effectiveStart + ((double) x / width) * effectiveBandwidth;
    }

    private double clampToVisible(double freq) {
        double effectiveStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double effectiveEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
        if (freq < effectiveStart) return effectiveStart;
        if (freq > effectiveEnd) return effectiveEnd;
        return freq;
    }

    private int getXForFrequency(double freq) {
        double effectiveStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double effectiveBandwidth = (viewEndFreq > 0) ? (viewEndFreq - viewStartFreq) : bandwidth;
        return (int) ((freq - effectiveStart) / effectiveBandwidth * width);
    }

    @Override
    public void addSelection(SpectrogramSelection selection) {
        selections.add(selection);
        assignProperties(selection);
        if (selectionAddedListener != null) {
            selectionAddedListener.accept(selection);
        }
        repaint();
    }

    @Override
    public void removeSelection(SpectrogramSelection selection) {
        selections.remove(selection);
        // Ensure any CENTER marker for this selection is removed immediately
        try {
            if (selection != null && selection.getId() != null) {
                clearCwCenterFor(selection.getId());
            }
        } catch (Throwable ignore) {}
        if (selectionRemovedListener != null) {
            selectionRemovedListener.accept(selection);
        }
        repaint();
    }

    @Override
    public void clearSelections() {
        selections.clear();
        try { clearAllCwCenters(); } catch (Throwable ignore) {}
        repaint();
    }

    @Override
    public List<SpectrogramSelection> getSelections() {
        return new ArrayList<>(selections);
    }

    private void createPalette() {
        palette = new int[256];
        for (int i = 0; i < 256; i++) {
            float val = i / 255.0f;
            Color c;

            switch (currentTheme) {
                case "Fire":
                    // Black -> Red -> Yellow -> White
                    if (val < 0.33f) {
                        c = new Color(val * 3, 0, 0);
                    } else if (val < 0.66f) {
                        c = new Color(1.0f, (val - 0.33f) * 3, 0);
                    } else {
                        c = new Color(1.0f, 1.0f, (val - 0.66f) * 3);
                    }
                    break;
                case "Grayscale":
                    c = new Color(val, val, val);
                    break;
                case "Ocean":
                    // Black -> Blue -> Cyan -> White
                    if (val < 0.33f) {
                        c = new Color(0, 0, val * 3);
                    } else if (val < 0.66f) {
                        c = new Color(0, (val - 0.33f) * 3, 1.0f);
                    } else {
                        c = new Color((val - 0.66f) * 3, 1.0f, 1.0f);
                    }
                    break;
                case "Spectrum":
                default:
                    // Low (0) -> Blue, Mid -> Green, High (255) -> Red
                    float hue = (240.0f - i * 240.0f / 255.0f) / 360.0f; // 240 (Blue) to 0 (Red)
                    c = Color.getHSBColor(hue, 1.0f, 1.0f);
                    break;
            }
            palette[i] = c.getRGB();
        }
    }

    public void setColorTheme(String theme) {
        this.currentTheme = theme;
        createPalette();
    }

    public void setScrollSpeed(int speed) {
        // CPU Waterfall speed is controlled by AudioCapture FFT size in this implementation
        // No internal speed control needed here as we just draw what we get.
    }

    public void setFrequencies(int frequencies) {
        this.bandwidth = frequencies;
        repaint();
    }

    public void setStartFrequency(long startFrequency) {
        // Preserve previous start to maintain offsets of markers and selections
        long prevStart = this.startFrequency;
        this.startFrequency = startFrequency;
        long delta = this.startFrequency - prevStart;

        // Move CW marker with the band (keep same offset from band start)
        if (cwIdMarkerEnabled && cwIdMarkerFrequencyHz > 0) {
            cwIdMarkerFrequencyHz = clampToVisible(cwIdMarkerFrequencyHz + delta);
        } else if (cwIdMarkerEnabled && cwIdMarkerFrequencyHz <= 0) {
            // Initialize CW marker near 700 Hz above start if not yet set
            cwIdMarkerFrequencyHz = startFrequency + 700;
        }

        // Keep user selections at absolute frequencies; do not shift with band start changes
        // They will naturally appear or hide based on the current visible view/band.
        repaint();

        // Notify listeners that the base/start frequency changed
        try {
            for (java.util.function.Consumer<Long> l : startFrequencyListeners) {
                try { l.accept(this.startFrequency); } catch (Throwable ignore) {}
            }
        } catch (Throwable ignore) {}
    }

    // Expose start frequency for tone computations
    public long getStartFrequency() {
        return startFrequency;
    }

    // Allow external listeners (e.g., UI/controllers) to react when the base/start frequency changes
    public void addStartFrequencyListener(java.util.function.Consumer<Long> listener) {
        if (listener != null) startFrequencyListeners.add(listener);
    }

    // CW ID marker API
    public void setCwIdMarkerEnabled(boolean enabled) {
        this.cwIdMarkerEnabled = enabled;
        repaint();
    }

    public boolean isCwIdMarkerEnabled() {
        return cwIdMarkerEnabled;
    }

    public void setCwIdMarkerFrequencyHz(double hz) {
        this.cwIdMarkerFrequencyHz = clampToVisible(hz);
        repaint();
    }

    public double getCwIdMarkerFrequencyHz() {
        return cwIdMarkerFrequencyHz;
    }

    public void setInputSampleRate(int sampleRate) {
        this.inputSampleRate = sampleRate;
    }

    // ---- CW AFC integration helpers ----
    public void setCwPitchListener(java.util.function.BiConsumer<Double, Double> listener) {
        this.cwPitchListener = listener;
    }
    public void setCwCenterMarkerEnabled(boolean enabled) {
        // AFC center markers are removed; force disabled and clear any existing markers
        this.cwCenterMarkerEnabled = false;
        cwCentersBySelection.clear();
        cwCenterDisplayBySelection.clear();
        repaint();
    }
    public void setCwTargetPitchHz(double hz) {
        this.cwTargetPitchHz = hz;
    }
    // Set explicit estimator window in absolute Hz (low <= high). Pass NaN to clear.
    public void setCwEstimatorWindowAbs(double lowAbsHz, double highAbsHz) {
        this.cwEstimatorWinLowAbsHz = lowAbsHz;
        this.cwEstimatorWinHighAbsHz = highAbsHz;
    }

    // Expose measured CW center (absolute Hz). Returns <0 if not available yet.
    public double getCwMeasuredCenterHz() {
        return cwMeasuredCenterHz;
    }

    // Provide a safe copy of the most recent FFT frame for parameter estimation (e.g., RTTY autodetect)
    public double[] getLastFftFrameCopy() {
        double[] src = lastFftFrame;
        if (src == null) return null;
        return Arrays.copyOf(src, src.length);
    }

    // Per-selection center APIs
    public void setCwMeasuredCenterFor(String selectionId, double absHz) {
        if (selectionId == null || absHz <= 0) return;
        cwCentersBySelection.put(selectionId, absHz);
        // Initialize display position if not present to avoid first-frame jump
        cwCenterDisplayBySelection.computeIfAbsent(selectionId, k -> absHz);
        repaint();
    }
    public void clearCwCenterFor(String selectionId) {
        if (selectionId == null) return;
        cwCentersBySelection.remove(selectionId);
        cwCenterDisplayBySelection.remove(selectionId);
        repaint();
    }
    public void clearAllCwCenters() {
        cwCentersBySelection.clear();
        cwCenterDisplayBySelection.clear();
        repaint();
    }

    // On-demand CW estimation within [lowAbsHz, highAbsHz] using last FFT frame
    public CwEst computeCwCenterAndSnr(double lowAbsHz, double highAbsHz) {
        try {
            double[] frame = lastFftFrame;
            if (frame == null || frame.length < 8) return new CwEst(false, 0, 0);
            if (highAbsHz <= lowAbsHz) return new CwEst(false, 0, 0);
            int bins = frame.length;
            if (bandwidth <= 0) return new CwEst(false, 0, 0);
            double binHz = bandwidth / (double) (bins - 1);
            double winLow = Math.max(startFrequency, lowAbsHz);
            double winHigh = Math.min(startFrequency + bandwidth, highAbsHz);
            if (winHigh <= winLow) return new CwEst(false, 0, 0);

            int iStart = (int) Math.max(0, Math.floor((winLow - startFrequency) / binHz));
            int iEnd = (int) Math.min(bins - 1, Math.ceil((winHigh - startFrequency) / binHz));

            double sumPow = 0.0;
            double sumPowFreq = 0.0;
            for (int i = iStart; i <= iEnd; i++) {
                double f = startFrequency + i * binHz;
                double p = Math.max(0.0, frame[i]);
                sumPow += p;
                sumPowFreq += p * f;
            }
            if (sumPow <= 0.0) return new CwEst(false, 0, 0);

            double centerForNoise = (winLow + winHigh) * 0.5;
            double n1L = centerForNoise - 300.0, n1H = centerForNoise - 150.0;
            double n2L = centerForNoise + 150.0, n2H = centerForNoise + 300.0;

            int n1Start = (int) Math.max(0, Math.floor((n1L - startFrequency) / binHz));
            int n1End = (int) Math.min(bins - 1, Math.ceil((n1H - startFrequency) / binHz));
            int n2Start = (int) Math.max(0, Math.floor((n2L - startFrequency) / binHz));
            int n2End = (int) Math.min(bins - 1, Math.ceil((n2H - startFrequency) / binHz));
            double noiseSum = 0.0; int noiseCount = 0;
            for (int i = n1Start; i <= n1End; i++) { double p = Math.max(0.0, frame[i]); noiseSum += p; noiseCount++; }
            for (int i = n2Start; i <= n2End; i++) { double p = Math.max(0.0, frame[i]); noiseSum += p; noiseCount++; }

            double centerAbs = sumPowFreq / Math.max(1e-12, sumPow);
            double noiseAvg = (noiseCount > 0) ? (noiseSum / noiseCount) : (sumPow / Math.max(1, (iEnd - iStart + 1)));
            double signalAvg = sumPow / Math.max(1, (iEnd - iStart + 1));
            double snrLin = Math.max(1e-9, signalAvg) / Math.max(1e-9, noiseAvg);
            double snrDb = 10.0 * Math.log10(snrLin);
            return new CwEst(true, centerAbs, snrDb);
        } catch (Throwable t) {
            return new CwEst(false, 0, 0);
        }
    }

    // Expose current total waterfall bandwidth (Hz)
    public int getBandwidth() {
        return bandwidth;
    }

    // Move a selection to a new absolute center frequency, clamp inside band, and notify listeners
    public void moveSelectionCenter(SpectrogramSelection sel, double desiredCenterAbsHz) {
        if (sel == null) return;
        double bandStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double bandEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
        double center = desiredCenterAbsHz;
        if (center < bandStart) center = bandStart;
        if (center > bandEnd) center = bandEnd;
        double bw = Math.max(1.0, sel.getBandwidth());
        double newStart = center - bw / 2.0;
        double newEnd = center + bw / 2.0;
        if (newStart < bandStart) { newStart = bandStart; newEnd = newStart + bw; }
        if (newEnd > bandEnd) { newEnd = bandEnd; newStart = newEnd - bw; }
        sel.setStartFrequency(newStart);
        sel.setBandwidth(bw);
        if (selectionChangedListener != null) selectionChangedListener.accept(sel);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        // Rendering is now paced by a Swing Timer (renderPacer) which writes lines into the ring buffer
        // and calls repaint() at a steady frame rate. paintComponent only draws the current image state.

        // Draw Ruler
        drawRuler(g);

        if (waterfallImage != null) {
            // Draw image below ruler using ring-buffered vertical scroll with 1:1 blits (no scaling)
            int imgW = waterfallImage.getWidth();
            int imgH = waterfallImage.getHeight();
            int head = ringHead; // volatile read

            int destTop = rulerHeight;

            // Draw First Segment (Head to Bottom of Buffer -> Top of Screen)
            int h1 = imgH - head;
            if (h1 > 0) {
                g.drawImage(waterfallImage.getSubimage(0, head, imgW, h1), 0, destTop, null);
            }

            // Draw Second Segment (Top of Buffer to Head -> Bottom of Screen)
            if (head > 0) {
                g.drawImage(waterfallImage.getSubimage(0, 0, imgW, head), 0, destTop + h1, null);
            }
        }

        // Draw Amateur Radio band plan overlays (boundaries and headers) over image, under selections
        Graphics2D g2_overlay = (Graphics2D) g.create();
        drawBandPlanOverlays(g2_overlay);
        g2_overlay.dispose();

        // Draw semi-transparent zoom drag overlay if active
        Graphics2D g2d = (Graphics2D) g.create();
        if (currentMode == Mode.ZOOM && zoomDragging && zoomDragStartX >= 0 && zoomDragCurrentX >= 0) {
            int x1 = Math.min(zoomDragStartX, zoomDragCurrentX);
            int x2 = Math.max(zoomDragStartX, zoomDragCurrentX);
            int w = Math.max(1, x2 - x1);
            // translucent plane over the waterfall region
            g2d.setColor(new Color(0, 150, 255, 60));
            g2d.fillRect(x1, rulerHeight, w, height - rulerHeight);
            // edge lines
            g2d.setColor(new Color(0, 200, 255));
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawLine(x1, rulerHeight, x1, height);
            g2d.drawLine(x2, rulerHeight, x2, height);
            g2d.setStroke(new BasicStroke(1.0f));
        }

        // Draw selections (hide when fully out of view)
        double effectiveStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double effectiveEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
        for (SpectrogramSelection s : selections) {
            // Skip if selection is completely outside the visible band
            if (s.getEndFrequency() <= effectiveStart || s.getStartFrequency() >= effectiveEnd) {
                continue;
            }
            int x1 = getXForFrequency(Math.max(s.getStartFrequency(), effectiveStart));
            int x2 = getXForFrequency(Math.min(s.getEndFrequency(), effectiveEnd));
            // Clamp to component bounds
            x1 = Math.max(0, Math.min(width - 1, x1));
            x2 = Math.max(0, Math.min(width - 1, x2));
            if (x2 < x1) { int tmp = x1; x1 = x2; x2 = tmp; }
            int w = Math.max(1, x2 - x1);

            Color c = s.getColor();
            // Translucent fill
            g2d.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 64));
            g2d.fillRect(x1, rulerHeight, w, height - rulerHeight);

            // Colored header bar to indicate mode
            int headerH = 8;
            g2d.setColor(c);
            g2d.fillRect(x1, rulerHeight, w, headerH);

            // Lock-on progress bar (if available)
            double lop = -1;
            try { lop = s.getLockOnPercent(); } catch (Throwable ignore) { }
            if (lop >= 0) {
                int barMargin = 2;
                int barY = rulerHeight + headerH + 2;
                int barH = 6;
                int barW = Math.max(1, w - barMargin * 2);
                // background
                g2d.setColor(new Color(0, 0, 0, 160));
                g2d.fillRect(x1 + barMargin, barY, barW, barH);
                // progress
                int progW = (int) Math.round(barW * Math.max(0, Math.min(1, lop)));
                g2d.setColor(new Color(0, 220, 0, 220));
                g2d.fillRect(x1 + barMargin, barY, progW, barH);
                // border
                g2d.setColor(new Color(255, 255, 255, 180));
                g2d.drawRect(x1 + barMargin, barY, barW, barH);
            }

            // Draw bars
            g2d.setColor(c);
            g2d.setStroke(new BasicStroke(2.0f));
            g2d.drawLine(x1, rulerHeight, x1, height);
            g2d.drawLine(x2, rulerHeight, x2, height);
            g2d.setStroke(new BasicStroke(1.0f));

            // Labels: show decoder/mode name at the top of the marker, then optional selection label beneath
            int textY = rulerHeight + 14;
            String modeName = null;
            try { modeName = s.getModeName(); } catch (Throwable ignore) { }
            if (modeName != null && !modeName.isEmpty()) {
                // Draw a subtle background for readability
                FontMetrics tfm = g2d.getFontMetrics();
                int padX = 3;
                int padY = 2;
                int textW = tfm.stringWidth(modeName);
                int textH = tfm.getAscent();
                int bgX = x1 + 2;
                int bgY = textY - textH + 1;
                g2d.setColor(new Color(0, 0, 0, 150));
                g2d.fillRoundRect(bgX - padX, bgY - padY, textW + padX * 2, textH + padY * 2, 6, 6);
                g2d.setColor(Color.WHITE);
                g2d.drawString(modeName, x1 + 4, textY);
                textY += 12;
            }
            if (s.getLabel() != null && !s.getLabel().isEmpty()) {
                g2d.setColor(new Color(230, 230, 230));
                g2d.drawString(s.getLabel(), x1 + 4, textY);
            }
        }

        // Draw hover selection if enabled (hidden in ZOOM mode)
        if (currentMode != Mode.ZOOM && hoverEnabled && mouseX >= 0) {
            double centerFreq = getFrequencyAt(mouseX);
            int x1 = getXForFrequency(centerFreq - hoverBandwidth / 2);
            int x2 = getXForFrequency(centerFreq + hoverBandwidth / 2);

            int w = x2 - x1;
            if (w < 1) w = 1;

            g2d.setColor(new Color(255, 255, 255, 50)); // Translucent white
            g2d.fillRect(x1, rulerHeight, w, height - rulerHeight);

            // Draw 2 bars (edges)
            g2d.setColor(Color.YELLOW);
            g2d.drawLine(x1, rulerHeight, x1, height);
            g2d.drawLine(x2, rulerHeight, x2, height);

            // Draw text
            g2d.setColor(Color.WHITE);
            String label = String.format("BW: %.0f Hz", hoverBandwidth);
            g2d.drawString(label, x1 + 2, rulerHeight + 24);
        }
        g2d.dispose();
    }

    /**
     * Updates the waterfall with a new line of frequency data (DSP/background thread safe).
     * This method performs all math and LUT mapping off the EDT and prepares a pre-colored row
     * in a reusable buffer. The EDT will consume this row in paintComponent and write it into the
     * BufferedImage's DataBufferInt at the current ring buffer head without shifting the image.
     *
     * Zero-allocation policy: no new arrays in the steady-state path.
     */
    public void updateSpectrogram(double[] magnitude) {
        if (magnitude == null || magnitude.length == 0) return;
        if (waterfallImage == null) return;

        // Snapshot latest FFT frame for per-selection estimations
        try {
            lastFftFrame = Arrays.copyOf(magnitude, magnitude.length);
        } catch (Throwable ignore) {}

        // AFC center markers removed — skip computing CW center estimates entirely
        if (false && cwCenterMarkerEnabled) {
            try {
                double bandStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
                double bandEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
                if (bandEnd > bandStart + 1 && !selections.isEmpty()) {
                    for (SpectrogramSelection s : selections) {
                        if (s == null || s.getId() == null) continue;
                        double selStart = Math.max(bandStart, Math.min(s.getStartFrequency(), s.getEndFrequency()));
                        double selEnd   = Math.min(bandEnd,   Math.max(s.getStartFrequency(), s.getEndFrequency()));
                        if (selEnd <= selStart) continue;
                        if (selEnd - selStart < 5.0) continue;
                        CwEst est = computeCwCenterAndSnr(selStart, selEnd);
                        if (est != null && est.valid && est.centerAbsHz >= bandStart && est.centerAbsHz <= bandEnd) {
                            setCwMeasuredCenterFor(s.getId(), est.centerAbsHz);
                        }
                    }
                }
            } catch (Throwable ignore) {}
        }

        // Prepare/refresh x→bin cache if needed (width/bins/zoom change)
        if (magnitude == null || magnitude.length == 0) return;
        if (waterfallImage == null) return;

        // Prepare/refresh x→bin cache if needed (width/bins/zoom change)
        final int bins = magnitude.length;
        // Snapshot zoom and band values to avoid races
        final long bandStartHz = this.startFrequency;
        final int bandWidthHz = this.bandwidth;
        final double vs = this.viewStartFreq;
        final double ve = this.viewEndFreq;
        double fracStart = 0.0;
        double fracEnd = 1.0;
        if (ve > 0 && ve > vs && bandWidthHz > 0) {
            fracStart = (vs - bandStartHz) / (double) bandWidthHz;
            fracEnd = (ve - bandStartHz) / (double) bandWidthHz;
            if (fracStart < 0) fracStart = 0;
            if (fracEnd > 1) fracEnd = 1;
            if (fracEnd < fracStart) { double t = fracStart; fracStart = fracEnd; fracEnd = t; }
        }
        boolean zoomChanged = (xToBinCacheFracStart < 0) || Math.abs(fracStart - xToBinCacheFracStart) > 1e-9 || Math.abs(fracEnd - xToBinCacheFracEnd) > 1e-9;
        if (xToBinCache == null || xToBinCacheWidth != width || xToBinCacheBins != bins || zoomChanged) {
            xToBinCache = new int[width];
            int maxBin = bins - 1;
            int binStart = (int) Math.round(fracStart * maxBin);
            int binEnd = (int) Math.round(fracEnd * maxBin);
            if (binEnd < binStart) { int t = binStart; binStart = binEnd; binEnd = t; }
            int span = Math.max(0, binEnd - binStart);
            int denom = Math.max(1, width - 1);
            for (int x = 0; x < width; x++) {
                int bi = binStart + (int) Math.round((x / (double) denom) * span);
                if (bi < 0) bi = 0;
                if (bi > maxBin) bi = maxBin;
                xToBinCache[x] = bi;
            }
            xToBinCacheWidth = width;
            xToBinCacheBins = bins;
            xToBinCacheFracStart = fracStart;
            xToBinCacheFracEnd = fracEnd;
        }

        // 1) Create a dedicated line buffer for this frame
        int[] frameLine = new int[width];

        // 2) Map bins → colors using precomputed LUT (no locking)
        final int w = width;
        for (int x = 0; x < w; x++) {
            double val = magnitude[xToBinCache[x]];
            int colorIndex = (int) (Math.log10(val + 1.0) * 50.0);
            if (colorIndex < 0) colorIndex = 0;
            if (colorIndex > 255) colorIndex = 255;
            frameLine[x] = palette[colorIndex];
        }

        // 3) Enqueue for render pacer to consume at a steady FPS
        lineQueue.offer(frameLine);
    }

    // Overload for compatibility if some producers pass double[][] where data[i][0] is the bin magnitude
    public void updateSpectrogram(double[][] data) {
        if (data == null || data.length == 0) return;
        if (waterfallImage == null) return;

        final int bins = data.length;
        // Snapshot zoom and band values to avoid races
        final long bandStartHz = this.startFrequency;
        final int bandWidthHz = this.bandwidth;
        final double vs = this.viewStartFreq;
        final double ve = this.viewEndFreq;
        double fracStart = 0.0;
        double fracEnd = 1.0;
        if (ve > 0 && ve > vs && bandWidthHz > 0) {
            fracStart = (vs - bandStartHz) / (double) bandWidthHz;
            fracEnd = (ve - bandStartHz) / (double) bandWidthHz;
            if (fracStart < 0) fracStart = 0;
            if (fracEnd > 1) fracEnd = 1;
            if (fracEnd < fracStart) { double t = fracStart; fracStart = fracEnd; fracEnd = t; }
        }
        boolean zoomChanged = (xToBinCacheFracStart < 0) || Math.abs(fracStart - xToBinCacheFracStart) > 1e-9 || Math.abs(fracEnd - xToBinCacheFracEnd) > 1e-9;
        if (xToBinCache == null || xToBinCacheWidth != width || xToBinCacheBins != bins || zoomChanged) {
            xToBinCache = new int[width];
            int maxBin = bins - 1;
            int binStart = (int) Math.round(fracStart * maxBin);
            int binEnd = (int) Math.round(fracEnd * maxBin);
            if (binEnd < binStart) { int t = binStart; binStart = binEnd; binEnd = t; }
            int span = Math.max(0, binEnd - binStart);
            int denom = Math.max(1, width - 1);
            for (int x = 0; x < width; x++) {
                int bi = binStart + (int) Math.round((x / (double) denom) * span);
                if (bi < 0) bi = 0;
                if (bi > maxBin) bi = maxBin;
                xToBinCache[x] = bi;
            }
            xToBinCacheWidth = width;
            xToBinCacheBins = bins;
            xToBinCacheFracStart = fracStart;
            xToBinCacheFracEnd = fracEnd;
        }

        // 1) Create a dedicated line buffer for this frame
        int[] frameLine = new int[width];

        // 2) Map bins → colors using precomputed LUT (no locking)
        final int w = width;
        for (int x = 0; x < w; x++) {
            int bx = xToBinCache[x];
            double val = (data[bx] != null && data[bx].length > 0) ? data[bx][0] : 0.0;
            int colorIndex = (int) (Math.log10(val + 1.0) * 50.0);
            if (colorIndex < 0) colorIndex = 0;
            if (colorIndex > 255) colorIndex = 255;
            frameLine[x] = palette[colorIndex];
        }

        // 3) Enqueue for render pacer to consume at a steady FPS
        lineQueue.offer(frameLine);
    }

    public void cleanup() {
        try {
            if (renderPacer != null) {
                renderPacer.stop();
            }
        } catch (Throwable ignore) {
        }
        lineQueue.clear();
    }

    @Override
    public JComponent getComponent() {
        return this;
    }


    // Draw amateur radio band boundaries and allowed ranges overlay using BandPlan + Settings
    private void drawBandPlanOverlays(Graphics2D g2d) {
        try {
            Preferences prefs = Preferences.userNodeForPackage(DeviceListPanel.class);
            String licStr = prefs.get("boundaryLicense", "OFF");
            boolean enDig = prefs.getBoolean("boundaryEnableDigital", false);
            boolean enVo  = prefs.getBoolean("boundaryEnableVoice", false);

            BandPlan.License lic = BandPlan.License.OFF;
            switch (licStr.toUpperCase()) {
                case "TECHNICIAN": lic = BandPlan.License.TECHNICIAN; break;
                case "GENERAL": lic = BandPlan.License.GENERAL; break;
                case "EXTRA": lic = BandPlan.License.EXTRA; break;
                default: lic = BandPlan.License.OFF; break;
            }
            if (lic == BandPlan.License.OFF || (!enDig && !enVo)) return; // nothing to draw

            // Visible span
            double effStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
            double effEnd   = (viewEndFreq > 0) ? viewEndFreq   : (startFrequency + bandwidth);
            if (effEnd <= effStart) return;

            int w = getWidth();
            int h = getHeight();
            int top = rulerHeight;
            int bottom = h;

            // Choose colors for categories
            Color digFill = new Color(0, 170, 255, 36);      // cyan-ish translucent
            Color digEdge = new Color(0, 200, 255, 160);
            Color voiFill = new Color(255, 220, 0, 28);      // amber translucent
            Color voiEdge = new Color(255, 200, 0, 160);
            Color bothFill = new Color(0, 255, 60, 20);      // greenish when both
            Color bothEdge = new Color(0, 230, 70, 160);

            // Footer bar will be drawn flush with the component bottom; no tall background strip
            int footerBaselineY = bottom - 1;

            // Fetch segments and draw those intersecting the view
            List<BandPlan.Segment> segs = BandPlan.segmentsFor(lic);
            for (BandPlan.Segment s : segs) {
                if (s.endHz <= effStart || s.startHz >= effEnd) continue; // off-screen
                int x1 = getXForFrequency(Math.max(s.startHz, effStart));
                int x2 = getXForFrequency(Math.min(s.endHz, effEnd));
                if (x2 < x1) { int t = x1; x1 = x2; x2 = t; }
                x1 = Math.max(0, Math.min(w-1, x1));
                x2 = Math.max(0, Math.min(w-1, x2));
                int ww = Math.max(1, x2 - x1);

                boolean hasDig = s.categories.contains(BandPlan.ModeCategory.DIGITAL);
                boolean hasVoi = s.categories.contains(BandPlan.ModeCategory.VOICE);
                boolean drawDig = enDig && hasDig;
                boolean drawVoi = enVo && hasVoi;
                if (!drawDig && !drawVoi) continue;

                // Blend logic: if both categories displayed, use combined color; otherwise category color
                Color fill, edge;
                if (drawDig && drawVoi) { fill = bothFill; edge = bothEdge; }
                else if (drawDig) { fill = digFill; edge = digEdge; }
                else { fill = voiFill; edge = voiEdge; }

                // Digital bandwidth bar anchored to the bottom and spanning full segment width
                int tagTextH = g2d.getFontMetrics().getAscent();
                int bandH = Math.max(2, Math.min(tagTextH, 12)); // no taller than text tag
                int bandY = bottom - bandH; // flush with bottom
                g2d.setColor(fill);
                g2d.fillRect(x1, bandY, ww, bandH);
                // Thin separator line at the top of the bar for definition
                g2d.setColor(edge);
                g2d.drawLine(x1, bandY, x1 + ww, bandY);
                
                // Keep boundary marker lines at segment edges
                g2d.setStroke(new BasicStroke(1.5f));
                g2d.drawLine(x1, top, x1, bottom);
                g2d.drawLine(x1 + ww, top, x1 + ww, bottom);
                g2d.setStroke(new BasicStroke(1.0f));

                // Always-visible tag in the footer indicating band + category (e.g., "40m Digital")
                String catText;
                if (drawDig && drawVoi) catText = "Digital/Voice";
                else if (drawDig) catText = "Digital";
                else catText = "Voice";
                String bandName = (s.bandLabel != null && !s.bandLabel.isEmpty()) ? s.bandLabel : "";
                String footerTag = bandName.isEmpty() ? catText : (bandName + " " + catText);
                if (!footerTag.isEmpty()) {
                    FontMetrics tfm = g2d.getFontMetrics();
                    int textW = tfm.stringWidth(footerTag);
                    int textH = tfm.getAscent();
                    int padX = 5;
                    int padY = 2;
                    int boxW = textW + padX * 2;
                    int boxH = textH + padY * 2;
                    // Place centered within the segment, vertically centered within the footer background
                    int cx = x1 + ww / 2;
                    int boxX = cx - boxW / 2;
                    // Ensure box stays within segment horizontally
                    if (boxX < x1 + 2) boxX = x1 + 2;
                    if (boxX + boxW > x1 + ww - 2) boxX = x1 + ww - 2 - boxW;
                    // Place the label just above the bottom bar
                    int bandYForTag = bottom - bandH;
                    int boxY = bandYForTag - 2 - boxH;
                    if (ww > 8 && boxW > 0 && boxH > 0 && boxY >= top) {
                        // Background for readability
                        g2d.setColor(new Color(0,0,0,160));
                        g2d.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
                        // Text
                        g2d.setColor(Color.WHITE);
                        g2d.drawString(footerTag, boxX + padX, boxY + padY + textH);
                    }
                }

                // Header bar and label inside the ruler area
                String label = (s.bandLabel != null ? s.bandLabel : "")
                        + ((s.notes != null && !s.notes.isEmpty()) ? ("  b7 " + s.notes) : "");
                if (!label.isEmpty()) {
                    FontMetrics tfm = g2d.getFontMetrics();
                    int textW = tfm.stringWidth(label);
                    int textH = tfm.getAscent();
                    int pad = 3;
                    int bx = x1 + 2;
                    int by = 2;
                    int bw = Math.min(ww - 4, textW + pad * 2);
                    if (bw > 8) {
                        // translucent dark background in ruler for readability
                        g2d.setColor(new Color(0,0,0,120));
                        g2d.fillRoundRect(bx, by, bw, textH + 4, 6, 6);
                        g2d.setColor(Color.WHITE);
                        g2d.drawString(label, bx + pad, by + textH);
                    }
                }
            }
        } catch (Throwable ignore) {
            // Fail-safe: never break the paint
        }
    }

    private void drawRuler(Graphics g) {
        int w = getWidth();

        // Background
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, w, rulerHeight);

        // Bottom line
        g.setColor(Color.GRAY);
        g.drawLine(0, rulerHeight - 1, w, rulerHeight - 1);

        // Determine effective view (zoom-aware)
        double effectiveStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
        double effectiveBandwidth = (viewEndFreq > 0) ? (viewEndFreq - viewStartFreq) : bandwidth;
        if (effectiveBandwidth <= 0) return;

        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();

        // Calculate tick interval for current view
        double pixelsPerHz = (double) w / effectiveBandwidth;
        double hzPerPixel = 1.0 / Math.max(1e-9, pixelsPerHz);

        double targetTickSpacingPx = 100;
        double targetTickSpacingHz = targetTickSpacingPx * hzPerPixel;

        // Find nice round number for spacing
        double tickSpacingHz = calculateNiceStep(targetTickSpacingHz);

        // Calculate start tick within the visible range
        double firstTickHz = Math.ceil(effectiveStart / tickSpacingHz) * tickSpacingHz;

        for (double freq = firstTickHz; freq <= effectiveStart + effectiveBandwidth + 1e-6; freq += tickSpacingHz) {
            int x = (int) Math.round((freq - effectiveStart) * pixelsPerHz);

            if (x >= 0 && x < w) {
                g.setColor(Color.GRAY);
                g.drawLine(x, rulerHeight - 5, x, rulerHeight - 1);

                String label = formatFrequency((long) Math.round(freq));
                int labelWidth = fm.stringWidth(label);
                g.setColor(Color.WHITE);
                g.drawString(label, x - labelWidth / 2, rulerHeight - 8);
            }
        }
        // Draw CW ID marker: two downward red triangles with label between (hide when out of view)
        if (cwIdMarkerEnabled && cwIdMarkerFrequencyHz > 0) {
            double effStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
            double effEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
            if (cwIdMarkerFrequencyHz >= effStart && cwIdMarkerFrequencyHz <= effEnd) {
                int x = getXForFrequency(cwIdMarkerFrequencyHz);
                // Keep inside component
                x = Math.max(0, Math.min(w - 1, x));
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(220, 30, 30));
                // Vertical indicator line
                g2.drawLine(x, 0, x, rulerHeight - 1);
                // Triangles geometry
                int apexY = 14;
                int baseY = 2;
                int triHalfW = 6;
                // Left triangle apex points to x-18
                int leftApexX = x - 18;
                int[] lx = {leftApexX - triHalfW, leftApexX + triHalfW, leftApexX};
                int[] ly = {baseY, baseY, apexY};
                g2.fillPolygon(lx, ly, 3);
                // Right triangle apex points to x+18
                int rightApexX = x + 18;
                int[] rx = {rightApexX - triHalfW, rightApexX + triHalfW, rightApexX};
                int[] ry = {baseY, baseY, apexY};
                g2.fillPolygon(rx, ry, 3);
                // Label centered at x
                String lbl = "CW ID";
                FontMetrics fm2 = g2.getFontMetrics();
                int tw = fm2.stringWidth(lbl);
                int ty = apexY + fm2.getAscent();
                // subtle shadow for readability
                g2.setColor(new Color(0,0,0,160));
                g2.drawString(lbl, x - tw / 2 + 1, ty + 1);
                g2.setColor(new Color(255, 80, 80));
                g2.drawString(lbl, x - tw / 2, ty);
                g2.dispose();
            }
        }
        // Draw CW Center markers per selection when enabled
        if (cwCenterMarkerEnabled) {
            double effStart = (viewEndFreq > 0) ? viewStartFreq : startFrequency;
            double effEnd = (viewEndFreq > 0) ? viewEndFreq : (startFrequency + bandwidth);
            for (Map.Entry<String, Double> eEntry : cwCenterDisplayBySelection.entrySet()) {
                double center = eEntry.getValue() != null ? eEntry.getValue() : -1;
                if (center <= 0) continue;
                if (center < effStart || center > effEnd) continue;
                int x = getXForFrequency(center);
                x = Math.max(0, Math.min(w - 1, x));
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Subtle glow line for better visibility
                g2.setColor(new Color(20, 120, 20, 120));
                g2.setStroke(new BasicStroke(3f));
                g2.drawLine(x, 0, x, rulerHeight - 1);
                g2.setStroke(new BasicStroke(1f));
                g2.setColor(new Color(40, 220, 40));
                g2.drawLine(x, 0, x, rulerHeight - 1);
                String lbl = "CENTER";
                FontMetrics fm2 = g2.getFontMetrics();
                int tw = fm2.stringWidth(lbl);
                int ty = 12;
                g2.setColor(new Color(0,0,0,160));
                g2.drawString(lbl, x - tw / 2 + 1, ty + 1);
                g2.setColor(new Color(120, 255, 120));
                g2.drawString(lbl, x - tw / 2, ty);
                g2.dispose();
            }
        }
    }

    private double calculateNiceStep(double range) {
        double exponent = Math.floor(Math.log10(range));
        double fraction = range / Math.pow(10, exponent);

        double niceFraction;
        if (fraction < 1.5) niceFraction = 1;
        else if (fraction < 3) niceFraction = 2;
        else if (fraction < 7) niceFraction = 5;
        else niceFraction = 10;

        return niceFraction * Math.pow(10, exponent);
    }

    private String formatFrequency(long hz) {
        if (hz >= 1_000_000) {
            return String.format("%.3f M", hz / 1_000_000.0);
        } else if (hz >= 1_000) {
            return String.format("%.1f k", hz / 1_000.0);
        } else {
            return String.format("%d", hz);
        }
    }

    @Override
    public void setHoverBandwidth(double bandwidth) {
        this.hoverBandwidth = bandwidth;
    }

    @Override
    public void setHoverEnabled(boolean enabled) {
        this.hoverEnabled = enabled;
        repaint();
    }

    @Override
    public void setSelectionListener(java.util.function.Consumer<Double> listener) {
        this.selectionListener = listener;
    }

    @Override
    public void setSelectionAddedListener(java.util.function.Consumer<SpectrogramSelection> listener) {
        this.selectionAddedListener = listener;
    }

    @Override
    public void setSelectionRemovedListener(java.util.function.Consumer<SpectrogramSelection> listener) {
        this.selectionRemovedListener = listener;
    }

    @Override
    public void setMode(Mode mode) {
        this.currentMode = mode;
        // Change cursor?
        if (mode == Mode.ZOOM) {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        } else {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    @Override
    public Mode getMode() {
        return currentMode;
    }

    @Override
    public void setViewRange(double startFreq, double endFreq) {
        this.viewStartFreq = startFreq;
        this.viewEndFreq = endFreq;
        repaint();
        for (java.util.function.Consumer<double[]> listener : viewChangeListeners) {
            listener.accept(new double[]{startFreq, endFreq});
        }
    }

    @Override
    public void resetView() {
        this.viewStartFreq = 0;
        this.viewEndFreq = 0;
        repaint();
        for (java.util.function.Consumer<double[]> listener : viewChangeListeners) {
            listener.accept(new double[]{startFrequency, startFrequency + bandwidth});
        }
    }

    @Override
    public void addViewChangeListener(java.util.function.Consumer<double[]> listener) {
        viewChangeListeners.add(listener);
    }

    @Override
    public SpectrogramSelection getActiveSelection() {
        return currentSelection;
    }

    @Override
    public void setActiveSelectionChangedListener(java.util.function.Consumer<SpectrogramSelection> listener) {
        this.activeSelectionChangedListener = listener;
    }

    @Override
    public void setSelectionChangedListener(java.util.function.Consumer<SpectrogramSelection> listener) {
        this.selectionChangedListener = listener;
    }

    @Override
    public void setDigitalPlacementEnabled(boolean enabled) {
        this.digitalPlacementEnabled = enabled;
    }

    @Override
    public boolean isDigitalPlacementEnabled() {
        return digitalPlacementEnabled;
    }

    @Override
    public void flashSelection(String id) {
        if (id == null) return;
        SpectrogramSelection target = null;
        for (SpectrogramSelection s : selections) {
            if (id.equals(s.getId())) {
                target = s;
                break;
            }
        }
        if (target != null) {
            final SpectrogramSelection targetSel = target;
            final Color orig = targetSel.getColor();
            Color bright = new Color(orig.getRed(), orig.getGreen(), orig.getBlue(), Math.min(255, orig.getAlpha() + 120));
            targetSel.setColor(bright);
            repaint();
            javax.swing.Timer t = new javax.swing.Timer(200, e -> {
                targetSel.setColor(orig);
                repaint();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    @Override
    public void setRadarScanRange(double startHz, double endHz) {
        this.radarStartHz = startHz;
        this.radarEndHz = endHz;
        repaint();
    }

    @Override
    public double getClickFrequency() {
        if (mouseX < 0) return -1;
        return getFrequencyAt(mouseX);
    }

    private void assignProperties(SpectrogramSelection selection) {
        if (selection.getId() == null) {
            int index = selectionCounter % SELECTION_COLORS.length;
            selection.setColor(SELECTION_COLORS[index]);
            char id = (char) ('A' + (selectionCounter % 26));
            selection.setId(String.valueOf(id));
            selection.setLabel("RX " + id);
            selectionCounter++;
        }
    }
}
