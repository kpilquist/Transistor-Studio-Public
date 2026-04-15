package org.example;

/**
 * Pure helper functions for Radar/Time Sweep math and CAT gating decisions.
 * These are extracted for unit testing and must mirror the logic used in DeviceListPanel.
 */
public final class RadarMath {

    private RadarMath() {}

    public enum Bucket { INSIDE, BETWEEN, BEYOND }

    /**
     * Wrapping time sweep: Start→End over periodMs, then dwell at End for dwellMs before wrapping.
     * @param startHz start frequency (Hz)
     * @param endHz end frequency (Hz)
     * @param periodMs duration to go from Start to End (ms)
     * @param dwellMs dwell time at End before wrapping (ms)
     * @param tMs time within cycle (ms), typically (now - cycleStart) % (period + dwell)
     */
    public static double wrapTargetHz(double startHz, double endHz, int periodMs, int dwellMs, long tMs) {
        double s = Math.min(startHz, endHz);
        double e = Math.max(startHz, endHz);
        int per = Math.max(1, periodMs);
        int dwell = Math.max(0, dwellMs);
        long cycle = (long) per + dwell;
        long t = modNonNegative(tMs, cycle);
        if (t < per) {
            double f = t / (double) per; // 0..1
            return s + f * (e - s);
        } else {
            return e; // dwell at end
        }
    }

    /**
     * Ping-pong sweep: dwell at Start, forward to End over periodMs, dwell at End, backward to Start over periodMs.
     * @param startHz start frequency (Hz)
     * @param endHz end frequency (Hz)
     * @param periodMs one-way duration (ms)
     * @param dwellMs dwell at each end (ms)
     * @param tMs time within cycle (ms), typically (now - turnStart) % (2*period + 2*dwell)
     */
    public static double pingPongTargetHz(double startHz, double endHz, int periodMs, int dwellMs, long tMs) {
        double s = Math.min(startHz, endHz);
        double e = Math.max(startHz, endHz);
        int per = Math.max(1, periodMs);
        int dwell = Math.max(0, dwellMs);
        long cycle = (long) per * 2L + (long) dwell * 2L;
        long t = modNonNegative(tMs, cycle);
        if (t < dwell) {
            return s; // dwell at start
        } else if (t < dwell + per) {
            double f = (t - dwell) / (double) per; // 0..1
            return s + f * (e - s);
        } else if (t < dwell + per + dwell) {
            return e; // dwell at end
        } else {
            double f = (t - (dwell + per + dwell)) / (double) per; // 0..1
            return e - f * (e - s);
        }
    }

    /**
     * Maps a frequency within [spanStartHz, spanEndHz] to an angle in degrees [0, 360]. Values outside are clamped.
     */
    public static double hzToAngleDegrees(double freqHz, double spanStartHz, double spanEndHz) {
        double start = Math.min(spanStartHz, spanEndHz);
        double end = Math.max(spanStartHz, spanEndHz);
        double span = Math.max(1.0, end - start);
        double norm = (freqHz - start) / span;
        double deg = 360.0 * norm;
        if (deg < 0) deg = 0;
        if (deg > 360) deg = 360;
        return deg;
    }

    /**
     * Computes the visual trail width in degrees from a bandwidth (Hz) and the visible span (Hz),
     * clamped to [2, 270] degrees.
     */
    public static double trailWidthDegreesFromBandwidth(double bandwidthHz, double spanHz) {
        double span = Math.max(1.0, spanHz);
        double trail = 360.0 * (Math.max(0.0, bandwidthHz) / span);
        trail = Math.max(2.0, Math.min(270.0, trail));
        return trail;
    }

    /**
     * Computes the buffer wedge total span in degrees for a ±bufferHz window, clamped to [0, 180].
     */
    public static double bufferSpanDegreesFromHz(double bufferHz, double spanHz) {
        double span = Math.max(1.0, spanHz);
        double deg = 360.0 * (2.0 * Math.max(0.0, bufferHz) / span);
        deg = Math.max(0.0, Math.min(180.0, deg));
        return deg;
    }

    /**
     * Classifies |delta| relative to buffer and buffer+hysteresis thresholds.
     * - INSIDE: |delta| <= bufferHz
     * - BEYOND: |delta| >= bufferHz + max(0, hysteresisHz)
     * - BETWEEN: otherwise
     */
    public static Bucket gateBucket(double absDeltaHz, int bufferHz, int hysteresisHz) {
        int buf = Math.max(0, bufferHz);
        int thr = buf + Math.max(0, hysteresisHz);
        if (absDeltaHz <= buf) return Bucket.INSIDE;
        if (absDeltaHz >= thr) return Bucket.BEYOND;
        return Bucket.BETWEEN;
    }

    /**
     * Decides if it is OK to send a CAT update now, given the time we have been beyond the buffer
     * and the time since the last update.
     */
    public static boolean shouldSendCat(long nowMs,
                                        Long beyondBufferSinceMs,
                                        long lastSendMs,
                                        int dwellMsBeyondBuffer,
                                        int minIntervalMs) {
        if (beyondBufferSinceMs == null) return false;
        long dwellOk = nowMs - beyondBufferSinceMs;
        long rateOk = nowMs - lastSendMs;
        return dwellOk >= Math.max(0, dwellMsBeyondBuffer)
                && rateOk >= Math.max(0, minIntervalMs);
    }

    private static long modNonNegative(long value, long mod) {
        if (mod <= 0) return 0L;
        long r = value % mod;
        if (r < 0) r += mod;
        return r;
    }
}
