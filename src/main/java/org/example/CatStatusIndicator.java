package org.example;

import javax.swing.*;
import java.awt.*;

/**
 * Small status indicator for CAT-over-TCP server/client activity.
 * States:
 *  - NOT_RUNNING: gray dot, tooltip indicates server stopped
 *  - IDLE: amber dot, tooltip indicates server running, no clients
 *  - CONNECTED: green dot, tooltip shows number of connected clients
 */
public class CatStatusIndicator extends JComponent {
    public enum State { NOT_RUNNING, IDLE, CONNECTED }

    private volatile State state = State.NOT_RUNNING;
    private volatile int clientCount = 0;

    public CatStatusIndicator() {
        setPreferredSize(new Dimension(70, 18));
        setToolTipText("CAT server: stopped");
    }

    public void setServerRunning(boolean running) {
        if (!running) {
            this.state = State.NOT_RUNNING;
            this.clientCount = 0;
            updateTooltip();
            repaint();
        } else {
            // If started and no clients yet, mark idle unless already connected
            if (clientCount > 0) this.state = State.CONNECTED; else this.state = State.IDLE;
            updateTooltip();
            repaint();
        }
    }

    public void setClientCount(int count) {
        this.clientCount = Math.max(0, count);
        if (count > 0) this.state = State.CONNECTED; else if (this.state != State.NOT_RUNNING) this.state = State.IDLE;
        updateTooltip();
        repaint();
    }

    private void updateTooltip() {
        switch (state) {
            case NOT_RUNNING -> setToolTipText("CAT server: stopped");
            case IDLE -> setToolTipText("CAT server: running — no clients connected");
            case CONNECTED -> setToolTipText("CAT server: " + clientCount + " client" + (clientCount==1?"":"s") + " connected");
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = getHeight();
            int y = (h - 12) / 2;

            // Choose color by state
            Color dot;
            switch (state) {
                case CONNECTED -> dot = new Color(0, 170, 0);
                case IDLE -> dot = new Color(220, 160, 0);
                default -> dot = new Color(150, 150, 150);
            }

            // Draw dot
            g2.setColor(dot);
            g2.fillOval(2, y, 12, 12);
            g2.setColor(dot.darker());
            g2.drawOval(2, y, 12, 12);

            // Draw label
            g2.setColor(getForeground() != null ? getForeground() : UIManager.getColor("Label.foreground"));
            String text = "CAT";
            FontMetrics fm = g2.getFontMetrics();
            int tx = 2 + 12 + 6;
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(text, tx, ty);
        } finally {
            g2.dispose();
        }
    }
}
