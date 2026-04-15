package org.example;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * CW Automatic Frequency Control (AFC) controller.
 *
 * Responsibilities:
 * - Accept pitch measurements (Hz, audio domain) and SNR estimates (dB)
 * - Average over recent frames (3-5) to smooth noise
 * - Apply SNR threshold gate (Gap Guard), hysteresis, freeze timer
 * - Enforce capture range relative to user's manual-tuned anchor
 * - Produce suggested radio frequency corrections at a safe cadence (driven externally)
 */
public class CWAfcController {
    // Configuration (can be exposed with setters if needed)
    private double targetPitchHz = 700.0;   // desired audio pitch
    private double snrThresholdDb = 10.0;   // only tune if SNR >= this
    private double hysteresisHz = 5.0;      // ignore tiny errors
    private long freezeMs = 500;            // do not change during gaps/pauses
    private double captureRangeHz = 200.0;  // max deviation from user anchor

    // State
    private boolean enabled = true;
    private final Deque<Double> recentPitch = new ArrayDeque<>();
    private final Deque<Double> recentSnr = new ArrayDeque<>();
    private int smoothingFrames = 5; // average of last N frames (3-5 recommended)

    private long lastStrongSignalMs = 0; // last time SNR above threshold
    private long lastSuggestMs = 0;      // last time we issued a suggestion (for info only)

    // Capture range anchor: the frequency the user set manually (or when AFC enabled)
    private long userAnchorFreq = 0;

    // Limits
    private static final int MAX_BUFFER = 8;

    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEnabled() { return enabled; }

    public void setTargetPitchHz(double targetPitchHz) { this.targetPitchHz = targetPitchHz; }
    public double getTargetPitchHz() { return targetPitchHz; }

    public void setSnrThresholdDb(double snrThresholdDb) { this.snrThresholdDb = snrThresholdDb; }
    public void setHysteresisHz(double hysteresisHz) { this.hysteresisHz = hysteresisHz; }
    public void setFreezeMs(long freezeMs) { this.freezeMs = freezeMs; }
    public void setCaptureRangeHz(double captureRangeHz) { this.captureRangeHz = captureRangeHz; }
    public void setSmoothingFrames(int frames) { this.smoothingFrames = Math.max(1, Math.min(7, frames)); }

    /** Set or reset the capture anchor (call on manual tune changes or on enabling AFC). */
    public void setUserAnchorFreq(long anchorHz) {
        this.userAnchorFreq = anchorHz;
    }
    /** Get current capture anchor (0 if not set). */
    public long getUserAnchorFreq() {
        return userAnchorFreq;
    }

    /**
     * Feed the AFC with a new measurement from DSP.
     * measuredPitchHz is audio-domain pitch (relative to baseband start), not absolute RF.
     * snrDb is estimated SNR in dB for the measured tone.
     */
    public synchronized void observe(double measuredPitchHz, double snrDb, long nowMs) {
        if (!enabled) return;
        // Maintain buffers
        recentPitch.addLast(measuredPitchHz);
        recentSnr.addLast(snrDb);
        while (recentPitch.size() > MAX_BUFFER) recentPitch.removeFirst();
        while (recentSnr.size() > MAX_BUFFER) recentSnr.removeFirst();
        if (snrDb >= snrThresholdDb) {
            lastStrongSignalMs = nowMs;
        }
    }

    /** Average of last N samples helper. */
    private double avgLastN(Deque<Double> dq, int n) {
        if (dq.isEmpty()) return Double.NaN;
        int cnt = Math.min(n, dq.size());
        double sum = 0;
        int i = 0;
        for (Double v : dq) {
            // iterate from oldest to newest; we want last N, so skip older items if needed
            if (dq.size() - i <= cnt) sum += (v != null ? v : 0.0);
            i++;
        }
        return sum / cnt;
    }

    /**
     * Compute suggested delta to apply to radio frequency in Hz.
     * Returns 0 when no action should be taken.
     * Sign convention: positive delta means increase radio frequency.
     */
    public synchronized long getSuggestedDeltaHz(long currentRadioFreqHz, long nowMs) {
        if (!enabled) return 0;
        // Gap Guard by SNR and Freeze timer
        double snrAvg = avgLastN(recentSnr, Math.min(smoothingFrames, recentSnr.size()));
        if (Double.isNaN(snrAvg) || snrAvg < snrThresholdDb) {
            // No reliable signal; respect freeze timer
            if (nowMs - lastStrongSignalMs < freezeMs) {
                return 0; // recently had signal, keep frozen
            }
            return 0; // long gap -> do nothing (no drift hunting)
        }

        // Average pitch
        double pitchAvg = avgLastN(recentPitch, Math.min(smoothingFrames, recentPitch.size()));
        if (Double.isNaN(pitchAvg)) return 0;

        double errorHz = pitchAvg - targetPitchHz; // e.g., 710 - 700 = +10 Hz
        if (Math.abs(errorHz) < hysteresisHz) return 0; // ignore tiny drifts

        // Capture range check relative to anchor
        if (userAnchorFreq != 0) {
            double projected = (double) currentRadioFreqHz + errorHz;
            if (Math.abs(projected - userAnchorFreq) > captureRangeHz) {
                return 0; // outside capture range, stop AFC walking
            }
        }

        lastSuggestMs = nowMs;
        return Math.round(errorHz);
    }
}
