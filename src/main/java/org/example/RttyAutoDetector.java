package org.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility to auto-detect RTTY parameters for a given selection and decoder.
 *
 * Capabilities:
 * - Estimate Mark/Space center and shift from the latest FFT frame in Waterfall.
 * - Probe small parameter grid (baud, inversion) and rank by RTTYDecoder.getLockPercent().
 */
public class RttyAutoDetector {

    public static class Est {
        public final boolean ok;
        public final double centerHzAbs; // absolute Hz within the waterfall band
        public final int shiftHz;
        Est(boolean ok, double centerHzAbs, int shiftHz) {
            this.ok = ok; this.centerHzAbs = centerHzAbs; this.shiftHz = shiftHz;
        }
        public static Est fail() { return new Est(false, 0, 0); }
    }

    public static class Candidate {
        public final double baud;
        public final boolean inverted;
        public final int shiftHz;
        public final int lockScore;
        public Candidate(double baud, boolean inverted, int shiftHz, int lockScore) {
            this.baud = baud; this.inverted = inverted; this.shiftHz = shiftHz; this.lockScore = lockScore;
        }
    }

    /**
     * Estimate RTTY center and shift by finding two strongest narrowband peaks
     * inside the selection window using the latest FFT frame.
     */
    public static Est estimateCenterAndShift(Waterfall wf, SpectrogramSelection sel) {
        if (wf == null || sel == null) return Est.fail();
        double[] frame = wf.getLastFftFrameCopy();
        if (frame == null || frame.length < 16) return Est.fail();
        int bins = frame.length;
        int bandwidth = wf.getBandwidth();
        if (bandwidth <= 0) return Est.fail();
        double start = wf.getStartFrequency();
        double binHz = bandwidth / (double) (bins - 1);

        double winStartAbs = Math.max(start, Math.min(sel.getStartFrequency(), sel.getEndFrequency()));
        double winEndAbs   = Math.min(start + bandwidth, Math.max(sel.getStartFrequency(), sel.getEndFrequency()));
        if (winEndAbs - winStartAbs < 60.0) return Est.fail();

        int iStart = (int) Math.max(0, Math.floor((winStartAbs - start) / binHz));
        int iEnd   = (int) Math.min(bins - 1, Math.ceil((winEndAbs - start) / binHz));
        if (iEnd - iStart < 4) return Est.fail();

        // Simple smoothing to reduce bin jitter
        double[] sm = new double[iEnd - iStart + 1];
        for (int i = iStart; i <= iEnd; i++) {
            double p = frame[i];
            double pL = (i > 0) ? frame[i - 1] : p;
            double pR = (i < bins - 1) ? frame[i + 1] : p;
            sm[i - iStart] = 0.25 * pL + 0.5 * p + 0.25 * pR;
        }

        // Find local maxima
        class Peak { int idx; double pow; }
        List<Peak> peaks = new ArrayList<>();
        for (int k = 1; k < sm.length - 1; k++) {
            if (sm[k] > sm[k - 1] && sm[k] >= sm[k + 1]) {
                Peak pk = new Peak();
                pk.idx = k + iStart; // index in full frame
                pk.pow = sm[k];
                peaks.add(pk);
            }
        }
        if (peaks.size() < 2) return Est.fail();

        // Keep the top few peaks within the window
        peaks.sort((a,b) -> Double.compare(b.pow, a.pow));
        int limit = Math.min(6, peaks.size());
        peaks = new ArrayList<>(peaks.subList(0, limit));
        peaks.sort((a,b) -> Integer.compare(a.idx, b.idx)); // sort by frequency

        // Choose the best-separated pair with plausible RTTY shift 60..1000 Hz
        Peak bestA = null, bestB = null; double bestPow = -1;
        for (int i = 0; i < peaks.size(); i++) {
            for (int j = i + 1; j < peaks.size(); j++) {
                Peak a = peaks.get(i), b = peaks.get(j);
                double fA = start + a.idx * binHz;
                double fB = start + b.idx * binHz;
                double shift = Math.abs(fB - fA);
                if (shift < 60.0 || shift > 1000.0) continue;
                double score = a.pow + b.pow; // could add separation weighting
                if (score > bestPow) { bestPow = score; bestA = a; bestB = b; }
            }
        }
        if (bestA == null || bestB == null) return Est.fail();

        double f1 = start + bestA.idx * binHz;
        double f2 = start + bestB.idx * binHz;
        double center = 0.5 * (f1 + f2);
        int shiftHz = (int) Math.round(Math.abs(f2 - f1));
        return new Est(true, center, shiftHz);
    }

    /**
     * Probe a small parameter grid and score using RTTYDecoder.getLockPercent().
     * Returns the best candidate. The caller may then apply it permanently.
     */
    public static Candidate autodetectParams(RTTYDecoder rd,
                                             float[] trialSamples,
                                             int sampleRate,
                                             double centerAudioHz,
                                             int shiftHz) {
        if (rd == null || trialSamples == null || trialSamples.length == 0 || sampleRate <= 0) return null;
        double[] baudList = new double[]{45.45, 50.0, 75.0};
        boolean[] invList = new boolean[]{false, true};

        int oldSq = rd.getLockSquelchPercent();
        // Suppress UI output during trials by forcing high squelch
        rd.setLockSquelchPercent(100);

        Candidate best = null;
        try {
            for (double baud : baudList) {
                for (boolean inv : invList) {
                    rd.setTargetFrequency((int) Math.round(centerAudioHz));
                    rd.setShift(shiftHz);
                    rd.setBaudRate(baud);
                    rd.setInverted(inv);

                    // Feed ~1 second worth in chunks; score average lock
                    int step = Math.max(256, sampleRate / 10);
                    int pos = 0; int acc = 0; int cnt = 0;
                    while (pos < trialSamples.length) {
                        int n = Math.min(step, trialSamples.length - pos);
                        float[] block = java.util.Arrays.copyOfRange(trialSamples, pos, pos + n);
                        rd.processSamples(block, sampleRate);
                        acc += rd.getLockPercent();
                        cnt++;
                        pos += n;
                    }
                    int score = (cnt > 0) ? (acc / cnt) : 0;
                    if (best == null || score > best.lockScore) {
                        best = new Candidate(baud, inv, shiftHz, score);
                    }
                }
            }
        } catch (Throwable t) {
            // ignore and return best so far
        } finally {
            // Restore squelch; caller will re-apply best params and normal squelch
            rd.setLockSquelchPercent(oldSq);
        }
        return best;
    }
}
