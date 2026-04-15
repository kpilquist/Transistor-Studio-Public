package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

public class RadarDisplay extends JPanel {
    private double currentAngle = 0; // Degrees 0-360 (Clockwise from North)
    private double trailWidthDegrees = 45; // visual sweep trail width
    private double bufferSpanDegrees = 0; // optional CAT/radar buffer overlay (total width)
    private boolean autoSweepEnabled = false;
    private boolean sweepVisible = true; // hide/show sweep needle and trail
    private int autoSweepPeriodMs = 2000; // full rotation time in ms when auto
    private long lastTickMs = 0L;
    private Timer sweepTimer;

    // Frequency scale: start/end of sweep (for circumference labeling) and processing bandwidth (outer radius semantic)
    private double sweepStartHz = Double.NaN;
    private double sweepEndHz = Double.NaN;
    private double processingBandwidthHz = Double.NaN;

    // Sweep direction mapping: true = angle increases left->right; false = right->left
    private boolean leftToRight = true;

    // Fade behavior: make blip persistence scale with sweep period
    // After one full rotation, blips will be reduced to this fraction of original intensity
    // e.g., 0.05 means ~5% remains after a full sweep (95% faded).
    private double fadeRetentionPerRotation = 0.05;
    private long lastFadeMs = 0L;

    private BufferedImage buffer;
    private int centerX, centerY, radius, size;
    
    public RadarDisplay() {
        setBackground(Color.BLACK);
        
        // Timer to simulate sweep (auto mode)
        sweepTimer = new Timer(40, e -> {
            long now = System.currentTimeMillis();
            if (lastTickMs == 0L) lastTickMs = now;
            long dt = now - lastTickMs;
            lastTickMs = now;
            if (autoSweepEnabled) {
                int per = Math.max(1, autoSweepPeriodMs);
                double deltaDeg = 360.0 * (dt / (double) per);
                currentAngle = (currentAngle + deltaDeg) % 360.0;
                repaint();
            }
        });
        sweepTimer.start();
    }

    public void setAngle(double angle) {
        setSweepAngleDegrees(angle);
    }

    public void setAutoSweepEnabled(boolean enabled) {
        boolean wasEnabled = this.autoSweepEnabled;
        this.autoSweepEnabled = enabled;
        // When enabling auto sweep, reset the sweep to the left edge and stabilize timing
        if (enabled && !wasEnabled) {
            this.currentAngle = 0.0; // start at left (Start frequency)
            this.lastTickMs = System.currentTimeMillis();
        }
        // In manual mode, ensure we repaint when external angle changes
        repaint();
    }

    public void setAutoSweepPeriodMs(int periodMs) {
        this.autoSweepPeriodMs = Math.max(1, periodMs);
    }

    public void setSweepVisible(boolean visible) {
        this.sweepVisible = visible;
        repaint();
    }

    public void setSweepAngleDegrees(double angle) {
        this.currentAngle = ((angle % 360) + 360) % 360; // normalize
        repaint();
    }

    public void setTrailWidthDegrees(double trailWidthDegrees) {
        if (Double.isNaN(trailWidthDegrees) || Double.isInfinite(trailWidthDegrees)) return;
        // clamp to sane range
        this.trailWidthDegrees = Math.max(2.0, Math.min(270.0, trailWidthDegrees));
        repaint();
    }

    public void setBufferSpanDegrees(double bufferSpanDegrees) {
        if (Double.isNaN(bufferSpanDegrees) || Double.isInfinite(bufferSpanDegrees)) return;
        this.bufferSpanDegrees = Math.max(0.0, Math.min(180.0, bufferSpanDegrees));
        repaint();
    }

    // Frequency scale setters
    public void setFrequencyScale(double startHz, double endHz) {
        this.sweepStartHz = startHz;
        this.sweepEndHz = endHz;
        repaint();
    }

    public void setProcessingBandwidthHz(double bwHz) {
        this.processingBandwidthHz = bwHz;
        repaint();
    }

    public void setSweepDirectionLeftToRight(boolean leftToRight) {
        this.leftToRight = leftToRight;
        repaint();
    }

    public void addReturn(double angle, double rangeNorm, double intensity) {
        if (buffer == null) return;
        // Ensure geometry is up to date
        int w = getWidth();
        int h = getHeight();
        int margin = 16;
        centerX = w / 2;
        centerY = h / 2;
        radius = Math.max(1, Math.min(centerX, centerY) - margin);

        // Normalize input
        double a = ((angle % 360.0) + 360.0) % 360.0;
        // Respect sweep direction: if not left->right, flip angle to maintain visual consistency
        if (!leftToRight) a = (360.0 - a);
        double theta = Math.toRadians(a - 90.0);

        double rn = Math.max(0.0, Math.min(1.0, rangeNorm));
        // Keep a small inner gap so center isn't over-bright
        double inner = Math.max(6.0, radius * 0.05);
        double r = inner + rn * (radius - inner);

        int x = centerX + (int) Math.round(r * Math.cos(theta));
        int y = centerY + (int) Math.round(r * Math.sin(theta));

        int val = (int) Math.max(0, Math.min(255, Math.round((float) intensity * 255f)));
        int rgb = (0xFF << 24) | (val << 8) | 0; // green channel

        // Write a small 2x2 dot for visibility
        for (int dy = -1; dy <= 0; dy++) {
            for (int dx = -1; dx <= 0; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && px < buffer.getWidth() && py >= 0 && py < buffer.getHeight()) {
                    buffer.setRGB(px, py, rgb);
                }
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        int w = getWidth();
        int h = getHeight();
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h) {
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }

        // Establish polar geometry
        int margin = 16;
        centerX = w / 2;
        centerY = h / 2;
        radius = Math.max(1, Math.min(centerX, centerY) - margin);

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Fade P7 buffer and draw it
        fadeBuffer();
        g2.drawImage(buffer, 0, 0, null);

        // Draw polar grid: outer circle, range rings and crosshairs
        g2.setColor(new Color(0, 50, 0));
        g2.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        for (int rSteps = 1; rSteps <= 3; rSteps++) {
            int rr = (int) Math.round(radius * (rSteps / 4.0));
            g2.drawOval(centerX - rr, centerY - rr, rr * 2, rr * 2);
        }
        // Crosshair (N-S, E-W)
        g2.drawLine(centerX - radius, centerY, centerX + radius, centerY);
        g2.drawLine(centerX, centerY - radius, centerX, centerY + radius);

        // Circular frequency scale and bandwidth annotation
        drawFrequencyScale(g2);
        drawBandwidthRings(g2);

        if (sweepVisible) {
            // Determine displayed angle taking direction into account
            double a = ((currentAngle % 360.0) + 360.0) % 360.0;
            if (!leftToRight) a = (360.0 - a);
            double theta = Math.toRadians(a - 90.0);

            // Trail as a fan of radial lines behind the needle
            int trailDeg = (int) Math.round(Math.max(2.0, Math.min(270.0, trailWidthDegrees)));
            trailDeg = Math.max(1, trailDeg);
            for (int i = 0; i < trailDeg; i++) {
                float alpha = (1.0f - (float) i / Math.max(1, trailDeg)) * 0.5f;
                double ang = Math.toRadians((a - i + 360.0) % 360.0 - 90.0);
                int x = centerX + (int) Math.round(radius * Math.cos(ang));
                int y = centerY + (int) Math.round(radius * Math.sin(ang));
                g2.setColor(new Color(0, 255, 0, (int) (alpha * 255)));
                g2.drawLine(centerX, centerY, x, y);
            }

            // Optional buffer wedge around the sweep line
            if (bufferSpanDegrees > 0.0) {
                double span = Math.max(0.0, Math.min(180.0, bufferSpanDegrees));
                double start = (a - span / 2.0 + 360.0) % 360.0;
                g2.setColor(new Color(0, 255, 128, 30));
                g2.fill(new Arc2D.Double(centerX - radius, centerY - radius, radius * 2.0, radius * 2.0,
                        start - 90.0, span, Arc2D.PIE));
                g2.setColor(new Color(0, 255, 128, 90));
                // Edge markers
                double ang1 = Math.toRadians((a - span / 2.0 + 360.0) % 360.0 - 90.0);
                double ang2 = Math.toRadians((a + span / 2.0) % 360.0 - 90.0);
                int x1 = centerX + (int) Math.round(radius * Math.cos(ang1));
                int y1 = centerY + (int) Math.round(radius * Math.sin(ang1));
                int x2 = centerX + (int) Math.round(radius * Math.cos(ang2));
                int y2 = centerY + (int) Math.round(radius * Math.sin(ang2));
                g2.drawLine(centerX, centerY, x1, y1);
                g2.drawLine(centerX, centerY, x2, y2);
            }

            // Bright sweep needle
            int xNow = centerX + (int) Math.round(radius * Math.cos(theta));
            int yNow = centerY + (int) Math.round(radius * Math.sin(theta));
            g2.setColor(Color.GREEN);
            g2.drawLine(centerX, centerY, xNow, yNow);
        }
    }

    private void drawFrequencyScale(Graphics2D g2) {
        if (Double.isNaN(sweepStartHz) || Double.isNaN(sweepEndHz)) return;
        if (!(sweepEndHz > sweepStartHz)) return;
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0, 200, 0));

        // Major ticks every 90 degrees with interpolated labels
        int[] majorAngles = new int[]{0, 90, 180, 270};
        for (int ang : majorAngles) {
            double t = ang / 360.0;
            double f = sweepStartHz + t * (sweepEndHz - sweepStartHz);
            drawTickWithLabel(g2, ang, formatFrequency((long)Math.round(f)));
        }
        // Explicit Start and End markers near 0° with slight offsets to avoid overlap
        drawTickWithLabel(g2, 2, "End: " + formatFrequency((long)Math.round(sweepEndHz)));
        drawTickWithLabel(g2, 358, "Start: " + formatFrequency((long)Math.round(sweepStartHz)));
    }

    private void drawBandwidthRings(Graphics2D g2) {
        if (Double.isNaN(processingBandwidthHz) || !(processingBandwidthHz > 0)) return;
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0, 200, 0));
        String text = "BW (center→outer): " + formatFrequency((long)Math.round(processingBandwidthHz));
        // Place text at bottom center outside the circle
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(text);
        int tx = centerX - tw / 2;
        int ty = centerY + radius + fm.getAscent() + 4;
        g2.drawString(text, tx, ty);
    }

    private void drawTickWithLabel(Graphics2D g2, double angleDeg, String label) {
        double rad = Math.toRadians(angleDeg - 90);
        int rOuter = radius + 3;
        int rInner = radius - 6;
        int x1 = centerX + (int)(rInner * Math.cos(rad));
        int y1 = centerY + (int)(rInner * Math.sin(rad));
        int x2 = centerX + (int)(rOuter * Math.cos(rad));
        int y2 = centerY + (int)(rOuter * Math.sin(rad));
        g2.drawLine(x1, y1, x2, y2);
        if (label != null && !label.isEmpty()) {
            FontMetrics fm = g2.getFontMetrics();
            int rText = radius + 10;
            int tx = centerX + (int)(rText * Math.cos(rad));
            int ty = centerY + (int)(rText * Math.sin(rad));
            int tw = fm.stringWidth(label);
            int th = fm.getAscent();
            // Center the text around (tx, ty) with small offset to keep inside panel
            g2.drawString(label, tx - tw/2, ty + th/2);
        }
    }

    private String formatFrequency(long hz) {
        double v = hz;
        if (Math.abs(v) >= 1_000_000) {
            return String.format("%.3f MHz", v / 1_000_000.0);
        } else if (Math.abs(v) >= 1000) {
            return String.format("%.3f kHz", v / 1000.0);
        } else {
            return String.format("%d Hz", hz);
        }
    }

    private void fadeBuffer() {
        if (buffer == null) return;
        long now = System.currentTimeMillis();
        if (lastFadeMs == 0L) lastFadeMs = now;
        long dt = Math.max(0L, now - lastFadeMs);
        lastFadeMs = now;

        int periodMs = Math.max(1, autoSweepPeriodMs);
        // Target: after one period, remaining intensity = fadeRetentionPerRotation
        // Per-frame retention r = pow(fadeRetentionPerRotation, dt/period)
        double frameRetention = Math.pow(fadeRetentionPerRotation, dt / (double) periodMs);
        // Fade amount is (1 - retention). Clamp to [0,1]
        float alpha = (float) Math.max(0.0, Math.min(1.0, 1.0 - frameRetention));

        Graphics2D g2d = buffer.createGraphics();
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, buffer.getWidth(), buffer.getHeight());
        g2d.dispose();
    }

    /**
     * Optional: set how much of a blip remains after one full rotation.
     * For example, 0.05 means ~5% remains after each full sweep.
     */
    public void setFadeRetentionPerRotation(double retention) {
        if (Double.isNaN(retention) || Double.isInfinite(retention)) return;
        this.fadeRetentionPerRotation = Math.max(0.0, Math.min(1.0, retention));
    }

    // Linear frequency scale along the bottom axis
    private void drawFrequencyScaleLinear(Graphics2D g2, int cX, int cY, int cW, int cH) {
        if (Double.isNaN(sweepStartHz) || Double.isNaN(sweepEndHz)) return;
        if (!(sweepEndHz > sweepStartHz)) return;
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0, 200, 0));
        int labelY = cY + cH + 12;

        int[] ticks = new int[]{0, 25, 50, 75, 100};
        for (int p : ticks) {
            double t = p / 100.0;
            int x = cX + (int)Math.round(t * cW);
            g2.drawLine(x, cY + cH, x, cY + cH + 6);
            double f = sweepStartHz + t * (sweepEndHz - sweepStartHz);
            String lab = formatFrequency((long)Math.round(f));
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(lab);
            g2.drawString(lab, x - tw/2, labelY + fm.getAscent());
        }

        // Corner labels for Start/End
        String startLab = "Start";
        String endLab = "End";
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(startLab, cX - fm.stringWidth(startLab) - 4, cY + cH + fm.getAscent());
        g2.drawString(endLab, cX + cW + 4, cY + cH + fm.getAscent());
    }

    // Bandwidth annotation text at top-left of the plot
    private void drawBandwidthText(Graphics2D g2, int cX, int cY, int cW, int cH) {
        if (Double.isNaN(processingBandwidthHz) || !(processingBandwidthHz > 0)) return;
        String text = "BW (proc): " + formatFrequency((long)Math.round(processingBandwidthHz));
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0, 200, 0));
        FontMetrics fm = g2.getFontMetrics();
        int tx = cX;
        int ty = Math.max(cY + fm.getAscent(), fm.getAscent());
        g2.drawString(text, tx, ty);
    }

    // Draw vertical bandwidth scale labels using processingBandwidthHz as full height (top/bottom = ±BW/2)
    private void drawVerticalBandwidthScale(Graphics2D g2, int cX, int cY, int cW, int cH) {
        if (Double.isNaN(processingBandwidthHz) || !(processingBandwidthHz > 0)) return;
        g2.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g2.setColor(new Color(0, 200, 0));
        FontMetrics fm = g2.getFontMetrics();

        long half = Math.round(processingBandwidthHz / 2.0);
        String topLab = "+" + formatFrequency(half);
        String botLab = "-" + formatFrequency(half);
        String midLab = "0";

        int xLabel = cX + 4;
        int yTop = cY + fm.getAscent();
        int yMid = cY + cH / 2 + fm.getAscent();
        // bottom label a bit above bottom to avoid clipping
        int yBot = Math.max(cY + cH - 2, cY + cH - fm.getDescent());

        g2.drawString(topLab, xLabel, yTop);
        g2.drawString(midLab, xLabel, yMid);
        g2.drawString(botLab, xLabel, yBot);

        // Optional small tick marks at left edge for top/mid/bottom
        int tickX1 = cX - 3;
        int tickX2 = cX;
        g2.drawLine(tickX1, cY, tickX2, cY);                   // top tick
        g2.drawLine(tickX1, cY + cH / 2, tickX2, cY + cH / 2); // mid tick
        g2.drawLine(tickX1, cY + cH, tickX2, cY + cH);         // bottom tick
    }
}
