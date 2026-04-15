package org.example;

import java.util.function.Consumer;

/**
 * Improved RTTY decoder with:
 * - Dual resonators (Goertzel-like) for Mark/Space with soft AGC
 * - Start-bit synchronized mid-bit sampling (5N1.5)
 * - Configurable baud rate, shift, inversion
 * - Basic stop-bit check and de-glitching
 */
public class RTTYDecoder implements SignalDecoder {
    private Consumer<String> textListener;

    // User parameters
    private int targetFrequency = 2125; // Center between Mark/Space
    private int shift = 170;            // Hz (85/170/425 etc.)
    private double baudRate = 45.45;    // Baud
    private boolean inverted = false;   // Swap Mark/Space

    // Decoder state
    private boolean figuresMode = false;

    // Bit timing state
    private double samplesPerBit;
    private double sampleClock = 0.0;
    private boolean lookingForStart = true;
    private int bitIndex = -1; // 0..4 data bits when >=0
    private int shiftRegister = 0;

    // Last symbol decision for edge detect
    private boolean lastDecision = true; // Idle Mark = true

    // Resonator state (stable 2-pole IIR / damped Goertzel)
    private double markQ1 = 0, markQ2 = 0;
    private double spaceQ1 = 0, spaceQ2 = 0;

    // Baseband smoothing state for decision metric
    private double dmSmooth = 0.0;
    private double prevDmSmooth = 0.0;

    // Simple AGC for magnitude normalization
    private double magAvg = 1e-6;

    // Lock/quality estimation (0..1)
    private double contrastEMA = 0.0;   // strength of Mark vs Space separation
    private double frameOkEMA = 0.0;    // validity of stop bits over time
    private int lockPercent = 0;        // cached 0..100 for GUI

    // Lock squelch (min lock % to emit decoded characters)
    private int lockSquelchPercent = 50; // default 50%

    // Lookup tables (Baudot ITA2)
    private static final char[] LETTERS = {
        0, 'E', '\n', 'A', ' ', 'S', 'I', 'U',
        '\r', 'D', 'R', 'J', 'N', 'F', 'C', 'K',
        'T', 'Z', 'L', 'W', 'H', 'Y', 'P', 'Q',
        'O', 'B', 'G', 0, 'M', 'X', 'V', 0
    };

    private static final char[] FIGURES = {
        0, '3', '\n', '-', ' ', '\'', '8', '7',
        '\r', '$', '4', '\'', ',', '!', ':', '(',
        '5', '"', ')', '2', '#', '6', '0', '1',
        '9', '?', '&', 0, '.', '/', ';', 0
    };

    // Configuration helpers
    public void setBaudRate(double baud) { this.baudRate = baud; }
    public void setShift(int hz) { this.shift = hz; }
    public void setInverted(boolean inv) { this.inverted = inv; }

    @Override
    public void setDecodedTextListener(Consumer<String> listener) {
        this.textListener = listener;
    }

    @Override
    public void setTargetFrequency(int frequency) {
        this.targetFrequency = frequency;
    }

    @Override
    public void processSamples(float[] samples, int sampleRate) {
        samplesPerBit = sampleRate / baudRate;

        // Compute actual mark/space audio freqs given center and shift
        double markFreq = targetFrequency + (shift / 2.0);
        double spaceFreq = targetFrequency - (shift / 2.0);

        // Stable 2-pole IIR resonators with radius r
        final double r = 0.99; // damping radius (close to 1.0)
        final double r2 = r * r;
        final double markTheta = 2 * Math.PI * markFreq / sampleRate;
        final double spaceTheta = 2 * Math.PI * spaceFreq / sampleRate;
        final double markCoeff = 2 * r * Math.cos(markTheta);
        final double spaceCoeff = 2 * r * Math.cos(spaceTheta);

        // Baseband smoothing factor for decision metric (simple 1-pole LPF)
        // Choose cutoff around 25-40 Hz to smooth audio-rate glitches but keep bit transitions
        final double dmFc = 35.0; // Hz
        final double dmAlpha = 1.0 - Math.exp(-2.0 * Math.PI * dmFc / Math.max(1.0, sampleRate));

        // AGC smoothing (slow)
        final double agcAlpha = 0.001;

        // Energy squelch threshold multiplier relative to slow average
        final double energyThreshFactor = 0.05; // 5% of average combined energy

        // Local accumulators for this block to update lock metric once per call
        double contrastAcc = 0.0;
        int contrastCount = 0;
        boolean frameStopOkThisBlock = false;
        boolean frameSawStopThisBlock = false;

        for (float s : samples) {
            // Stable damped-Goertzel style resonators
            double mq0 = s + markCoeff * markQ1 - r2 * markQ2;
            markQ2 = markQ1; // shift states (no artificial decay applied to states)
            markQ1 = mq0;

            double sq0 = s + spaceCoeff * spaceQ1 - r2 * spaceQ2;
            spaceQ2 = spaceQ1;
            spaceQ1 = sq0;

            // Magnitude estimate using damped Goertzel energies
            double markMag = markQ1 * markQ1 + markQ2 * markQ2 - markCoeff * markQ1 * markQ2;
            double spaceMag = spaceQ1 * spaceQ1 + spaceQ2 * spaceQ2 - spaceCoeff * spaceQ1 * spaceQ2;

            // Combined raw energy
            double total = markMag + spaceMag;

            // Update slow AGC average
            magAvg += agcAlpha * (total - magAvg);

            // Decision metric in -1..+1 (Mark minus Space, normalized by energy)
            double denom = total > 1e-12 ? total : 1e-12;
            double decisionMetric = (markMag - spaceMag) / denom;

            // 1-pole low-pass smoothing of baseband decision metric
            prevDmSmooth = dmSmooth;
            dmSmooth += dmAlpha * (decisionMetric - dmSmooth);

            // Apply inversion to metric and decision
            double dmSigned = inverted ? -dmSmooth : dmSmooth;
            boolean decision = dmSigned >= 0.0; // Mark = true, Space = false

            // Energy squelch check
            boolean energyOk = total >= (magAvg * energyThreshFactor);

            // Accumulate decision contrast only when above energy floor
            if (energyOk) {
                double contrast = Math.min(1.0, Math.max(0.0, Math.abs(dmSigned)));
                contrastAcc += contrast;
                contrastCount++;
            }

            // Debounced start detection: trigger on smoothed metric crossing a definitive threshold
            if (lookingForStart) {
                if (energyOk) {
                    double prevSigned = inverted ? -prevDmSmooth : prevDmSmooth;
                    if (prevSigned > -0.2 && dmSigned < -0.5) {
                        // Detected solid Mark->Space start edge
                        sampleClock = samplesPerBit * 1.5; // center of first data bit
                        bitIndex = 0;
                        shiftRegister = 0;
                        lookingForStart = false;
                    }
                }
            } else {
                // Run sampling clock
                sampleClock -= 1.0;
                if (sampleClock <= 0.0) {
                    // Sample current bit at center
                    if (bitIndex < 5) {
                        if (decision) {
                            shiftRegister |= (1 << bitIndex); // LSB first
                        }
                        bitIndex++;
                        sampleClock += samplesPerBit; // schedule next data bit
                    } else {
                        // Expect stop bit (Mark). Only update lock metrics if energy is OK
                        boolean stopIsMark = decision; // sample center of stop
                        if (energyOk) {
                            frameSawStopThisBlock = true;
                            if (stopIsMark) {
                                frameStopOkThisBlock = true;
                                emitChar(shiftRegister);
                            }
                        }
                        // Whether or not stop was valid, go back to search for new start
                        lookingForStart = true;
                        bitIndex = -1;
                    }
                }
            }

            lastDecision = decision;
        }

        // Update EMAs and lock percentage once per block
        double alphaFast = 0.05;  // responsiveness for contrast
        double alphaSlow = 0.10;  // responsiveness for frame validity
        if (contrastCount > 0) {
            double avgContrast = contrastAcc / contrastCount; // 0..1
            contrastEMA = (1.0 - alphaFast) * contrastEMA + alphaFast * avgContrast;
        } else {
            // slight decay towards 0 when no info
            contrastEMA *= (1.0 - alphaFast);
        }
        if (frameSawStopThisBlock) {
            double sample = frameStopOkThisBlock ? 1.0 : 0.0;
            frameOkEMA = (1.0 - alphaSlow) * frameOkEMA + alphaSlow * sample;
        } else {
            // no frames, gently decay
            frameOkEMA *= (1.0 - 0.02);
        }
        double score = 0.6 * contrastEMA + 0.4 * frameOkEMA; // 0..1
        if (score < 0) score = 0;
        if (score > 1) score = 1;
        lockPercent = (int) Math.round(score * 100.0);
    }

    /** Returns 0..100 estimate of current RTTY lock quality. */
    public int getLockPercent() { return lockPercent; }

    /** Gets/sets the minimum lock percentage required to output decoded text. */
    public int getLockSquelchPercent() { return lockSquelchPercent; }
    public void setLockSquelchPercent(int p) {
        if (p < 0) p = 0; if (p > 100) p = 100;
        this.lockSquelchPercent = p;
    }

    private void emitChar(int code) {
        if (code == 0x1B) { // FIGS
            figuresMode = true;
            return;
        }
        if (code == 0x1F) { // LTRS
            figuresMode = false;
            return;
        }
        char c = figuresMode ? FIGURES[code & 0x1F] : LETTERS[code & 0x1F];
        if (c != 0 && textListener != null) {
            // Apply lock squelch: only emit when lock is at/above threshold
            if (lockPercent >= lockSquelchPercent) {
                textListener.accept(String.valueOf(c));
            }
        }
    }
}

