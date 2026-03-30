package org.example;

import java.awt.Color;

public class SpectrogramSelection {
    private double startFrequency;
    private double bandwidth;
    private Color color;
    private String label;
    private String id; // e.g. "A", "B"
    // Optional: name of the digital mode (e.g., "RTTY", "PSK31"). Not used internally for logic.
    private String modeName;
    // Optional lock-on percentage [0..1]; negative value means not available.
    private double lockOnPercent = -1.0;

    public SpectrogramSelection(double startFrequency, double bandwidth) {
        this(startFrequency, bandwidth, new Color(255, 255, 255, 50)); // Default translucent white
    }

    public SpectrogramSelection(double startFrequency, double bandwidth, Color color) {
        this.startFrequency = startFrequency;
        this.bandwidth = bandwidth;
        this.color = color;
        this.label = "";
    }

    public double getStartFrequency() {
        return startFrequency;
    }

    public void setStartFrequency(double startFrequency) {
        this.startFrequency = startFrequency;
    }

    public double getBandwidth() {
        return bandwidth;
    }

    public void setBandwidth(double bandwidth) {
        this.bandwidth = bandwidth;
    }

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getModeName() {
        return modeName;
    }

    public void setModeName(String modeName) {
        this.modeName = modeName;
    }

    public double getLockOnPercent() {
        return lockOnPercent;
    }

    public void setLockOnPercent(double lockOnPercent) {
        if (Double.isNaN(lockOnPercent)) {
            this.lockOnPercent = -1.0;
            return;
        }
        // allow -1 meaning N/A; otherwise clamp to [0,1]
        if (lockOnPercent < 0 && lockOnPercent != -1.0) lockOnPercent = 0;
        if (lockOnPercent > 1) lockOnPercent = 1;
        this.lockOnPercent = lockOnPercent;
    }

    public double getEndFrequency() {
        return startFrequency + bandwidth;
    }
}
