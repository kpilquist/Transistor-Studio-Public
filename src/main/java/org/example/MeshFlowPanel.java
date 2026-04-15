package org.example;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MeshFlowPanel renders a right-side UI element visualizing the VXP network.
 *
 * Features:
 * - Connected to NodeTracker to get active nodes
 * - Maintains where callsigns are drawn via nodePositions (Map<String, Point2D>)
 * - Draws nodes as circles with callsign labels
 * - Cyan flashing ring for any node currently the Source of an active packet pulse
 * - Animates packet pulses (moving dots) between nodes at ~30 FPS
 * - Lays out nodes on a horizontal ellipse, with a special SERVER node in the center
 */
public class MeshFlowPanel extends JPanel {
    // Public data model requested
    private final Map<String, Point2D> nodePositions = new HashMap<>();

    // Animation model
    private final List<PacketPulse> activePulses = new CopyOnWriteArrayList<>();

    private final NodeTracker nodeTracker;
    private final javax.swing.Timer timer; // 30 FPS

    // Layout/config
    private static final int FPS = 30;
    private static final int NODE_RADIUS = 14; // pixels
    private static final int SERVER_RADIUS = 18; // pixels
    private static final int MARGIN = 24; // border margin
    private static final Color BG_GRID = new Color(255, 255, 255, 12);
    private static final Color NODE_FILL = new Color(90, 140, 210);
    private static final Color NODE_STROKE = new Color(30, 70, 140);
    private static final Color SERVER_FILL = new Color(100, 180, 120);
    private static final Color SERVER_STROKE = new Color(20, 110, 60);

    private static final String SERVER_NODE = "SERVER";

    // Blinking state for cyan ring (source nodes)
    private long blinkStartMs = System.currentTimeMillis();

    public MeshFlowPanel(NodeTracker tracker) {
        this.nodeTracker = Objects.requireNonNull(tracker, "tracker");
        setOpaque(true);
        setBackground(UIManager.getColor("Panel.background"));

        // 30 FPS timer
        int delay = 1000 / FPS;
        this.timer = new javax.swing.Timer(delay, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                boolean needsRepaint = false;

                // advance pulses
                for (int i = activePulses.size() - 1; i >= 0; i--) {
                    PacketPulse p = activePulses.get(i);
                    p.progress += 1.0 / 30.0; // about 1s duration
                    if (p.progress >= 1.0) {
                        activePulses.remove(i);
                    } else {
                        needsRepaint = true;
                    }
                }

                // Occasionally repaint to update blinking even without pulses
                needsRepaint = needsRepaint || (System.currentTimeMillis() - blinkStartMs) > 100;

                if (needsRepaint) {
                    repaint();
                }
            }
        });
        this.timer.setRepeats(true);
        this.timer.start();

        setPreferredSize(new Dimension(360, 220));
        setMinimumSize(new Dimension(200, 150));
        setToolTipText("Mesh flow visualization");
    }

    /**
     * Exposes node positions as an unmodifiable view.
     */
    public Map<String, Point2D> getNodePositions() {
        return Collections.unmodifiableMap(nodePositions);
    }

    /**
     * Starts an animation of a packet moving from source to destination.
     * - Blue dot for MSG
     * - Orange dot for FILE_* (FILE_META, FILE_CHUNK)
     */
    public void animatePacket(VaraPacket packet) {
        if (packet == null) return;
        String src = trimCall(packet.getSourceCallsign());
        String dst = trimCall(packet.getDestCallsign());
        if (src == null || src.isEmpty()) src = SERVER_NODE; // fallback
        if (dst == null || dst.isEmpty()) dst = SERVER_NODE;

        // Ensure positions exist (trigger layout computation before using)
        layoutNodes();
        Point2D srcPt = nodePositions.getOrDefault(src, nodePositions.get(SERVER_NODE));
        Point2D dstPt = nodePositions.getOrDefault(dst, nodePositions.get(SERVER_NODE));
        if (srcPt == null || dstPt == null) return;

        Color pulseColor = colorFor(packet.getPacketType());
        activePulses.add(new PacketPulse(src, dst, 0.0, packet.getPacketType(), pulseColor));
        repaint();
    }

    private static Color colorFor(VaraPacket.PacketType t) {
        if (t == null) return new Color(200, 200, 200);
        switch (t) {
            case MSG:
                return new Color(80, 170, 255); // Blue
            case FILE_META:
            case FILE_CHUNK:
                return new Color(255, 160, 60); // Orange
            default:
                return new Color(200, 200, 200);
        }
    }

    private static String trimCall(String s) {
        if (s == null) return null;
        // VaraPacket may use 6-char padded strings; trim spaces
        return s.trim().toUpperCase();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Background subtle grid
        drawBackgroundGrid(g2);

        // Compute layout every frame to adapt to size/active nodes
        layoutNodes();

        // Draw edges for pulses first (optional faint lines)
        drawPulseTrails(g2);

        // Draw nodes
        drawNodes(g2);

        // Draw active pulses on top
        drawPulses(g2);

        g2.dispose();
    }

    private void drawBackgroundGrid(Graphics2D g2) {
        int w = getWidth();
        int h = getHeight();
        g2.setColor(BG_GRID);
        int step = 16;
        for (int x = MARGIN; x < w - MARGIN; x += step) {
            g2.drawLine(x, MARGIN, x, h - MARGIN);
        }
        for (int y = MARGIN; y < h - MARGIN; y += step) {
            g2.drawLine(MARGIN, y, w - MARGIN, y);
        }
    }

    private void layoutNodes() {
        // Gather active nodes and make sure SERVER is included
        List<String> nodes = new ArrayList<>();
        nodes.addAll(nodeTracker.getActiveNodes());
        // Normalize to trimmed uppercase (NodeTracker already uppercases, but be defensive)
        for (int i = 0; i < nodes.size(); i++) {
            String n = nodes.get(i);
            if (n != null) nodes.set(i, n.trim().toUpperCase());
        }
        if (!nodes.contains(SERVER_NODE)) nodes.add(SERVER_NODE);
        // Stable order
        Collections.sort(nodes);

        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());

        double cx = w / 2.0;
        double cy = h / 2.0;
        double a = Math.max(10, (w - 2.0 * MARGIN) / 2.0 - 10); // major radius (horizontal)
        double b = Math.max(10, (h - 2.0 * MARGIN) / 2.0 - 10); // minor radius (vertical)

        // Place SERVER at center
        nodePositions.clear();
        nodePositions.put(SERVER_NODE, new Point2D.Double(cx, cy));

        // Place other nodes around ellipse, evenly spaced (skip SERVER)
        List<String> others = new ArrayList<>();
        for (String n : nodes) {
            if (!SERVER_NODE.equals(n)) others.add(n);
        }
        int nCount = others.size();
        if (nCount > 0) {
            double startAngle = Math.toRadians(0); // start at angle 0 to the right
            double step = 2 * Math.PI / nCount; // full ellipse
            for (int i = 0; i < nCount; i++) {
                double ang = startAngle + i * step;
                double x = cx + a * Math.cos(ang);
                double y = cy + b * Math.sin(ang);
                nodePositions.put(others.get(i), new Point2D.Double(x, y));
            }
        }
    }

    private void drawNodes(Graphics2D g2) {
        // Determine which nodes are currently source of an active pulse
        long now = System.currentTimeMillis();
        double phase = ((now - blinkStartMs) % 600) / 600.0; // 0..1 cycles ~1.6 Hz
        boolean on = phase < 0.5;

        for (Map.Entry<String, Point2D> e : nodePositions.entrySet()) {
            String call = e.getKey();
            Point2D p = e.getValue();
            int r = SERVER_NODE.equals(call) ? SERVER_RADIUS : NODE_RADIUS;
            double x = p.getX();
            double y = p.getY();

            // Determine ring highlight if any pulse originates here
            boolean isSource = isActiveSource(call);

            // Circle
            Shape circle = new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r);
            if (SERVER_NODE.equals(call)) {
                g2.setColor(SERVER_FILL);
                g2.fill(circle);
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(SERVER_STROKE);
                g2.draw(circle);
            } else {
                g2.setColor(NODE_FILL);
                g2.fill(circle);
                g2.setStroke(new BasicStroke(1.6f));
                g2.setColor(NODE_STROKE);
                g2.draw(circle);
            }

            // Flashing cyan ring for active sources
            if (isSource && on) {
                g2.setColor(new Color(0, 255, 255));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(new Ellipse2D.Double(x - (r + 3), y - (r + 3), 2 * (r + 3), 2 * (r + 3)));
            }

            // Label (callsign) centered beneath
            String label = call;
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(label);
            int th = fm.getAscent();
            g2.setColor(new Color(230, 230, 230));
            g2.drawString(label, (int) Math.round(x - tw / 2.0), (int) Math.round(y + r + th + 2));
        }
    }

    private void drawPulseTrails(Graphics2D g2) {
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 6f}, 0f));
        for (PacketPulse p : activePulses) {
            Point2D a = nodePositions.get(p.startNode);
            Point2D b = nodePositions.get(p.endNode);
            if (a == null || b == null) continue;
            g2.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), 60));
            g2.drawLine((int) a.getX(), (int) a.getY(), (int) b.getX(), (int) b.getY());
        }
    }

    private void drawPulses(Graphics2D g2) {
        for (PacketPulse p : activePulses) {
            Point2D a = nodePositions.get(p.startNode);
            Point2D b = nodePositions.get(p.endNode);
            if (a == null || b == null) continue;
            double t = clamp(p.progress, 0.0, 1.0);
            double x = lerp(a.getX(), b.getX(), t);
            double y = lerp(a.getY(), b.getY(), t);
            int r = 5;
            g2.setColor(Color.BLACK);
            g2.fill(new Ellipse2D.Double(x - (r + 1), y - (r + 1), 2 * (r + 1), 2 * (r + 1)));
            g2.setColor(p.color);
            g2.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
        }
    }

    private boolean isActiveSource(String call) {
        for (PacketPulse p : activePulses) {
            if (p.startNode.equals(call)) return true;
        }
        return false;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /**
     * PacketPulse describes an animated packet moving from startNode to endNode.
     */
    public static class PacketPulse {
        public final String startNode;
        public final String endNode;
        public double progress; // 0.0 .. 1.0
        public final VaraPacket.PacketType type;
        public final Color color;

        public PacketPulse(String startNode, String endNode, double progress, VaraPacket.PacketType type, Color color) {
            this.startNode = Objects.requireNonNull(startNode);
            this.endNode = Objects.requireNonNull(endNode);
            this.progress = progress;
            this.type = type;
            this.color = color == null ? new Color(200, 200, 200) : color;
        }
    }
}
