package org.example;

import java.util.function.Consumer;

/**
 * Very simple CW (Morse) decoder.
 *
 * This is a basic tone/key detector using a Goertzel filter around targetFrequency
 * and a simple threshold with timing accumulation to translate tone on/off into
 * dits and dahs at an approximate WPM. This is not robust but provides a starting point.
 */
public class CWDecoder implements SignalDecoder {
    private Consumer<String> listener;
    private volatile Consumer<LevelSample> levelListener; // optional UI meter listener
    private int targetFrequency = 700; // Hz audio tone to look for

    // Goertzel state
    private double sPrev = 0, sPrev2 = 0;
    private double coeff = 0;
    private int sampleRate = 48000;

    // Keying detection
    private double powerAvg = 0;         // running average of tone power
    private double thresh = 0;           // adaptive threshold
    private boolean keyDown = false;     // keying state
    private int keyLenSamples = 0;       // consecutive samples in current state
    private int gapLenSamples = 0;

    // Timing to dits/dahs
    private double wpm = 20.0;           // smoothing display/legacy value
    private StringBuilder currentChar = new StringBuilder();
    private StringBuilder currentWord = new StringBuilder(); // Buffer to form words/sentences

    // Control Settings
    private boolean autoWpm = true;
    private double userWpm = 20.0;
    private double squelch = 2.0;

    // Adaptive pulse classification (derive dit/dah from signal)
    // Online 2-means centroids in samples for key-down durations
    private double ditCentroid = -1; // in samples
    private double dahCentroid = -1; // in samples
    private int ditCount = 0;
    private int dahCount = 0;
    private double ditMin = Double.POSITIVE_INFINITY, ditMax = 0; // observed bounds (samples)
    private double dahMin = Double.POSITIVE_INFINITY, dahMax = 0;
    private static final double KMEANS_ALPHA = 0.05; // learning rate for centroid update

    // UI level metering (optional)
    private volatile long lastLevelEmitNanos = 0L; // for throttling callbacks

    public static final class LevelSample {
        public final double power;       // instantaneous Goertzel power (block)
        public final double threshold;   // current adaptive threshold
        public final boolean keyDown;    // current keying state
        public final double ratio;       // power / threshold (>=0). ~1.0 is trip point
        public LevelSample(double power, double threshold, boolean keyDown, double ratio) {
            this.power = power;
            this.threshold = threshold;
            this.keyDown = keyDown;
            this.ratio = ratio;
        }
    }

    public void setLevelListener(Consumer<LevelSample> l) {
        this.levelListener = l;
    }

    public void setAutoWpm(boolean auto) {
        this.autoWpm = auto;
        if (!auto) {
            this.wpm = this.userWpm;
        }
    }

    public void setWpm(double w) {
        this.userWpm = w;
        if (!autoWpm) {
            this.wpm = w;
        }
    }

    public void setSquelch(double sq) {
        this.squelch = sq;
    }

    public double getCurrentWpm() {
        if (autoWpm && ditCentroid > 0) {
            double ditMs = (ditCentroid * 1000.0) / sampleRate;
            if (ditMs > 0) return 1200.0 / ditMs;
        }
        return this.wpm;
    }

    @Override
    public void processSamples(float[] samples, int sr) {
        if (samples == null || samples.length == 0) return;
        if (sr != sampleRate) {
            sampleRate = sr;
            updateCoeff();
        }
        double k = 0.995; // smoothing
        for (int i = 0; i < samples.length; i++) {
            double x = samples[i];
            double s = x + coeff * sPrev - sPrev2;
            sPrev2 = sPrev;
            sPrev = s;
            // Goertzel power every N samples (~10 ms)
            int window = Math.max(16, sampleRate / 100); // 10 ms
            if ((i % window) == window - 1) {
                double power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2;
                // reset block
                sPrev = 0; sPrev2 = 0;
                // adaptive average
                powerAvg = k * powerAvg + (1 - k) * power;
                if (thresh == 0) thresh = powerAvg * squelch;
                thresh = 0.99 * thresh + 0.01 * (powerAvg * squelch);

                // Emit level sample for UI meter (throttled ~30 fps)
                Consumer<LevelSample> ll = levelListener;
                if (ll != null) {
                    long now = System.nanoTime();
                    // ~33 ms between updates
                    if (now - lastLevelEmitNanos > 33_000_000L) {
                        lastLevelEmitNanos = now;
                        double ratio = (thresh > 0.0) ? (power / Math.max(thresh, 1e-12)) : 0.0;
                        try { ll.accept(new LevelSample(power, thresh, keyDown, ratio)); } catch (Throwable ignore) {}
                    }
                }

                boolean down = power > thresh;
                int step = window; // step samples advanced
                if (down == keyDown) {
                    // continue current state
                    if (down) {
                        keyLenSamples += step;
                    } else {
                        gapLenSamples += step;
                        // Periodic check for long gaps to force emit word
                        double dit = ditLengthSamples();
                        if (gapLenSamples > dit * 7 && currentChar.length() > 0) {
                             flushChar();
                        }
                        if (gapLenSamples > dit * 10 && currentWord.length() > 0) {
                            emitWord(true); // emit with trailing space
                        }
                    }
                } else {
                    // state change
                    if (keyDown) {
                        // key up: finalize a dit/dah
                        handleKeyUp(keyLenSamples);
                        keyLenSamples = 0;
                        gapLenSamples = step;
                    } else {
                        // key down: finalize a gap
                        handleGap(gapLenSamples);
                        gapLenSamples = 0;
                        keyLenSamples = step;
                    }
                    keyDown = down;
                }
            }
        }
    }

    private void handleKeyUp(int lenSamples) {
        // Use adaptive dit estimate for noise rejection and classification
        double ditEst = ditLengthSamples();
        // Reject very short spikes (likely noise)
        if (lenSamples < Math.max(8, (int) (ditEst * 0.25))) {
            return;
        }

        // Bootstrap centroids if unknown
        if (ditCentroid <= 0 && dahCentroid <= 0) {
            // first real pulse -> assume dit candidate
            ditCentroid = lenSamples;
            ditCount = 1;
            ditMin = Math.min(ditMin, lenSamples);
            ditMax = Math.max(ditMax, lenSamples);
            currentChar.append('.');
        } else if (ditCentroid > 0 && dahCentroid <= 0) {
            // Only dit known yet: decide if this is likely dah (>= ~2x dit) or another dit
            if (lenSamples >= ditCentroid * 1.8) {
                dahCentroid = lenSamples;
                dahCount = 1;
                dahMin = Math.min(dahMin, lenSamples);
                dahMax = Math.max(dahMax, lenSamples);
                currentChar.append('-');
            } else {
                // update dit centroid
                ditCentroid = (1 - KMEANS_ALPHA) * ditCentroid + KMEANS_ALPHA * lenSamples;
                ditCount++;
                ditMin = Math.min(ditMin, lenSamples);
                ditMax = Math.max(ditMax, lenSamples);
                currentChar.append('.');
            }
        } else {
            // Both centroids known: classify by nearest centroid
            double dDit = Math.abs(lenSamples - ditCentroid);
            double dDah = Math.abs(lenSamples - dahCentroid);
            if (dDit <= dDah) {
                // classify as dit
                ditCentroid = (1 - KMEANS_ALPHA) * ditCentroid + KMEANS_ALPHA * lenSamples;
                ditCount++;
                ditMin = Math.min(ditMin, lenSamples);
                ditMax = Math.max(ditMax, lenSamples);
                currentChar.append('.');
            } else {
                dahCentroid = (1 - KMEANS_ALPHA) * dahCentroid + KMEANS_ALPHA * lenSamples;
                dahCount++;
                dahMin = Math.min(dahMin, lenSamples);
                dahMax = Math.max(dahMax, lenSamples);
                currentChar.append('-');
            }
        }

        // Update display WPM estimate from measured dit, if enabled
        if (autoWpm && ditCentroid > 0) {
            double ditMs = (ditCentroid * 1000.0) / sampleRate;
            double est = (ditMs > 0) ? (1200.0 / ditMs) : wpm;
            // Smooth for UI stability
            wpm = 0.9 * wpm + 0.1 * est;
        }
    }

    private void handleGap(int lenSamples) {
        double dit = ditLengthSamples();
        if (lenSamples >= dit * 7 * 0.8) {
            // word gap
            flushChar();
            emitWord(true);
        } else if (lenSamples >= dit * 3 * 0.8) {
            // character gap
            flushChar();
        } // else intra-element gap, do nothing
    }

    private void flushChar() {
        if (currentChar.length() == 0) return;
        String morse = currentChar.toString();
        String letter = MorseTable.fromPattern(morse);
        if (letter != null) {
            currentWord.append(letter);
        } else {
            // If unknown pattern, maybe append a placeholder or ignore
            // Ignore for cleaner text
        }
        currentChar.setLength(0);
    }

    private void emitWord(boolean trailingSpace) {
        if (currentWord.length() > 0) {
            String w = currentWord.toString();
            // Filter out single E or T if they might be noise, unless they are valid words like "I", "A", etc.
            // "E" and "T" are common noise decodes.
            if ((w.equals("E") || w.equals("T")) && trailingSpace) {
                // It might just be noise.
                // We'll drop it to keep the chat clean
                currentWord.setLength(0);
                return; 
            }
            if (trailingSpace) w += " ";
            
            if (listener != null) {
                listener.accept(w);
            }
            currentWord.setLength(0);
        }
    }

    private double ditLengthSamples() {
        // Prefer directly measured dit from the signal when available
        if (autoWpm && ditCentroid > 0) {
            return ditCentroid;
        }
        // Fallback to WPM-based estimate (user or smoothed auto value)
        double ditMs = 1200.0 / wpm;
        return ditMs * sampleRate / 1000.0;
    }

    private void updateCoeff() {
        double norm = 2 * Math.PI * targetFrequency / sampleRate;
        coeff = 2 * Math.cos(norm);
        sPrev = sPrev2 = 0;
    }

    @Override
    public void setDecodedTextListener(Consumer<String> listener) {
        this.listener = listener;
    }

    @Override
    public void setTargetFrequency(int frequency) {
        this.targetFrequency = frequency;
        updateCoeff();
    }

    // Minimal Morse lookup helper
    private static class MorseTable {
        private static final java.util.Map<String, String> MAP = new java.util.HashMap<>();
        static {
            put(".-", "A"); put("-...", "B"); put("-.-.", "C"); put("-..", "D"); put(".", "E");
            put("..-.", "F"); put("--.", "G"); put("....", "H"); put("..", "I"); put(".---", "J");
            put("-.-", "K"); put(".-..", "L"); put("--", "M"); put("-.", "N"); put("---", "O");
            put(".--.", "P"); put("--.-", "Q"); put(".-.", "R"); put("...", "S"); put("-", "T");
            put("..-", "U"); put("...-", "V"); put(".--", "W"); put("-..-", "X"); put("-.--", "Y");
            put("--..", "Z");
            put(".----", "1"); put("..---", "2"); put("...--", "3"); put("....-", "4"); put(".....", "5");
            put("-....", "6"); put("--...", "7"); put("---..", "8"); put("----.", "9"); put("-----", "0");
            
            // Common punctuation
            put(".-.-.-", "."); put("--..--", ","); put("..--..", "?"); put("-.-.--", "!");
            put("-....-", "-"); put("-..-.", "/"); put(".--.-.", "@"); put("-.--.", "(");
            put("-.--.-", ")"); put("---...", ":"); put("-.-.-.", ";"); put(".-..-.", "\"");
            put(".----.", "'"); put("-...-", "="); put(".-.-.", "+");
        }
        private static void put(String k, String v) { MAP.put(k, v); }
        static String fromPattern(String p) { return MAP.get(p); }
    }
}