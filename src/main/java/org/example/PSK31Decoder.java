package org.example;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Improved PSK31 decoder using a BPSK Costas loop for carrier/phase tracking
 * and Gardner timing recovery for symbol detection at 31.25 Bd. Maintains Varicode decoding with
 * "00" character separator handling.
 */
public class PSK31Decoder implements SignalDecoder {
    private Consumer<String> textListener;
    // Optional: listener for detected active callsign from free-text streams
    private Consumer<String> callsignListener;
    private volatile String activeCallsign;
    private final Map<String, Integer> callsignRunCounts = new HashMap<>();
    private String lastCallCandidate = null;
    private int callCandidateStreak = 0;
    private static final Pattern CALLSIGN_PATTERN = Pattern.compile("\\b[13][A-Z][0-9][A-Z]{1,3}\\b|\\b[A-Z]{1,2}[0-9][A-Z]{1,3}\\b");

    private int targetFrequency = 1000;
    private static final double BAUD_RATE = 31.25;

    // User controls (AFC permanently disabled for PSK)
    private boolean afcEnabled = false;

    // Lock/quality estimation (0..100)
    private double errMagEMA = 0.5; // Starts at poor lock
    private int lockPercent = 0;
    private int lockSquelchPercent = 50;

    private static final Map<String, Character> REVERSE_VARICODE = new HashMap<>();

    static {
        // Populate reverse map
        addMapping(' ', "1");
        addMapping('e', "11");
        addMapping('t', "101");
        addMapping('a', "1011");
        addMapping('o', "10111");
        addMapping('i', "1101");
        addMapping('n', "1111");
        addMapping('s', "101111");
        addMapping('h', "10101");
        addMapping('r', "101011");
        addMapping('d', "101101");
        addMapping('l', "11011");
        addMapping('u', "110111");
        addMapping('c', "1011011");
        addMapping('m', "111011");
        addMapping('w', "1101011");
        addMapping('f', "111101");
        addMapping('g', "1111011");
        addMapping('y', "1011101");
        addMapping('p', "1110111");
        addMapping('b', "1011111");
        addMapping('v', "11111101");
        addMapping('k', "10111111");
        addMapping('j', "11110101");
        addMapping('x', "11011111");
        addMapping('q', "110111111");
        addMapping('z', "111010101");

        // Numbers
        addMapping('0', "10110111");
        addMapping('1', "10111101");
        addMapping('2', "11101101");
        addMapping('3', "11111111");
        addMapping('4', "101110111");
        addMapping('5', "101011011");
        addMapping('6', "101101011");
        addMapping('7', "110101101");
        addMapping('8', "110101011");
        addMapping('9', "110110111");

        // Punctuation
        addMapping('.', "1010111");
        addMapping(',', "10111011");
        addMapping('?', "101010111");
        addMapping('-', "101101");
        addMapping('/', "1111101");
        addMapping('\n', "11101");

        // Uppercase
        addMapping('A', "11111011");
        addMapping('B', "11101011");
        addMapping('C', "10101101");
        addMapping('D', "10110101");
        addMapping('E', "1110111");
        addMapping('F', "11011011");
        addMapping('G', "11111101");
        addMapping('H', "101010101");
        addMapping('I', "1111111");
        addMapping('J', "111111101");
        addMapping('K', "101111101");
        addMapping('L', "11010111");
        addMapping('M', "10111011");
        addMapping('N', "11011101");
        addMapping('O', "10101011");
        addMapping('P', "1110101");
        addMapping('Q', "111111111");
        addMapping('R', "10101111");
        addMapping('S', "1101111");
        addMapping('T', "1101101");
        addMapping('U', "10101011");
        addMapping('V', "110110101");
        addMapping('W', "101011101");
        addMapping('X', "101110101");
        addMapping('Y', "101111011");
        addMapping('Z', "1010101101");
    }

    private static void addMapping(char c, String code) {
        REVERSE_VARICODE.put(code, c);
    }

    // NCO / Costas loop state
    private double ncoPhase = 0.0;
    private double ncoFreq = 0.0;        // radians per sample, set per block
    private double phaseErrorInt = 0.0;  // integrator for loop

    // FIR baseband filter (moving average as simple RRC approximation)
    private double[] firTaps;            // normalized taps
    private double[] iDelay;             // I delay line
    private double[] qDelay;             // Q delay line
    private int firLen = 0;
    private int firIndex = 0;            // circular index
    private int lastSampleRate = 0;      // to (re)build taps when rate changes

    // Symbol timing recovery (Gardner)
    private double omega = 0.0;          // nominal samples per symbol
    private double mu = 0.0;             // fractional timing phase [0, omega)
    private final double omegaMinFactor = 0.7;
    private final double omegaMaxFactor = 1.3;

    // Previous symbol phase for differential detection
    private double prevSymPhase = 0.0;
    private boolean havePrevSym = false;

    private final StringBuilder currentSymbolBits = new StringBuilder();
    private final StringBuilder outBuffer = new StringBuilder();
    private static final int EMIT_CHUNK_SIZE = 24;

    @Override
    public void setDecodedTextListener(Consumer<String> listener) {
        this.textListener = listener;
    }

    @Override
    public void setTargetFrequency(int frequency) {
        this.targetFrequency = frequency;
    }

    @Override
    public synchronized void processSamples(float[] samples, int sampleRate) {
        // Initialize FIR and history buffers if needed (outside per-sample loop)
        if (sampleRate != lastSampleRate || firTaps == null) {
            lastSampleRate = sampleRate;
            // Nominal samples per symbol
            double sps = sampleRate / BAUD_RATE;
            omega = sps;
            // FIR length ~ half-symbol moving average, at least 8, even number preferred
            int desired = (int) Math.round(sps * 0.5);
            if (desired < 8) desired = 8;
            firLen = desired | 1; // make it odd for symmetric center
            // Allocate arrays (not inside the per-sample loop)
            firTaps = new double[firLen];
            iDelay = new double[firLen];
            qDelay = new double[firLen];
            // Simple moving-average taps
            double tap = 1.0 / firLen;
            for (int i = 0; i < firLen; i++) firTaps[i] = tap;
            firIndex = 0;
            mu = 0.0;
            prevSymPhase = 0.0;
            havePrevSym = false;
        }

        // Loop parameters for Costas
        final double loopKp = 0.02;
        final double loopKi = 0.0002;

        // Set NCO freq for this block
        ncoFreq = 2 * Math.PI * targetFrequency / sampleRate;

        // Accumulate simple signal and error magnitudes for lock estimate
        double sigAcc = 0.0;
        double errAcc = 0.0;
        int accCount = 0;

        // Gardner timing: store mid-sample (half symbol) and previous decided symbol sample (I only for TED)
        double midI = 0.0;
        double prevISym = 0.0;
        boolean havePrevISym = false;

        double prevMu = mu;

        for (int n = 0; n < samples.length; n++) {
            float s = samples[n];

            // 1) NCO Mixer to baseband
            double loI = Math.cos(ncoPhase);
            double loQ = -Math.sin(ncoPhase);
            double iMix = s * loI;
            double qMix = s * loQ;

            // 2) FIR Low Pass Filter (moving average)
            // Update circular delay lines
            iDelay[firIndex] = iMix;
            qDelay[firIndex] = qMix;
            // Convolution (explicit loop, zero allocation)
            double iF = 0.0, qF = 0.0;
            int di = firIndex;
            for (int k = 0; k < firLen; k++) {
                double c = firTaps[k];
                iF += c * iDelay[di];
                qF += c * qDelay[di];
                di--; if (di < 0) di = firLen - 1;
            }
            // Advance delay index
            firIndex++;
            if (firIndex == firLen) firIndex = 0;

            // 3) Costas Loop (phase correction from filtered baseband)
            // Exact BPSK phase error
            double phaseErr = Math.atan2(qF, iF);
            // Map phase to [-pi/2, pi/2] for BPSK symmetry
            if (phaseErr > Math.PI / 2) phaseErr -= Math.PI;
            else if (phaseErr < -Math.PI / 2) phaseErr += Math.PI;

            // Track magnitudes for lock estimate (EVM proxy)
            double mag = Math.sqrt(iF*iF + qF*qF);
            sigAcc += mag;
            errAcc += Math.abs(phaseErr) * mag;
            accCount++;

            // Update NCO (phase/frequency) — AFC removed: no integral term
            phaseErrorInt = 0.0;
            ncoPhase += ncoFreq + loopKp * phaseErr;
            if (ncoPhase > Math.PI) ncoPhase -= 2 * Math.PI;
            if (ncoPhase < -Math.PI) ncoPhase += 2 * Math.PI;

            // 4) Gardner Timing Recovery
            // Detect half-symbol crossing to capture mid sample
            double halfThresh = omega * 0.5;
            if (prevMu < halfThresh && (mu + 1.0) >= halfThresh) {
                midI = iF; // capture mid symbol sample (I only sufficient for BPSK)
            }

            // Advance fractional timing
            prevMu = mu;
            mu += 1.0;

            // Full symbol decision when mu crosses omega
            if (mu >= omega) {
                mu -= omega;

                // Decision sample at symbol boundary (current filtered sample)
                double iSym = iF;
                double qSym = qF;

                // Gardner TED error using I only: e = sign(i_k - i_{k-1}) * midI / mag
                if (havePrevISym) {
                    double eTed = Math.signum(iSym - prevISym) * (midI / (mag + 1e-9));
                    // Update omega (symbol rate) and mu (timing phase)
                    omega += 0.005 * eTed;
                    // Clamp omega to reasonable bounds around nominal
                    double omegaMin = omegaMinFactor * (sampleRate / BAUD_RATE);
                    double omegaMax = omegaMaxFactor * (sampleRate / BAUD_RATE);
                    if (omega < omegaMin) omega = omegaMin;
                    if (omega > omegaMax) omega = omegaMax;
                    
                    mu += 0.05 * eTed;
                    if (mu < 0) mu = 0; // keep within [0, omega)
                    if (mu >= omega) mu = omega - 1e-6;
                }
                prevISym = iSym;
                havePrevISym = true;

                // 5) Differential Phase Detection using atan2
                double phase = Math.atan2(qSym, iSym);
                if (havePrevSym) {
                    double d = phase - prevSymPhase;
                    // Wrap to [-pi, pi]
                    if (d > Math.PI) d -= 2 * Math.PI; else if (d < -Math.PI) d += 2 * Math.PI;
                    int bit = (Math.abs(d) < (Math.PI * 0.5)) ? 1 : 0; // 0 => phase reversal (~pi)
                    processBit(bit);
                }
                prevSymPhase = phase;
                havePrevSym = true;
            }
        }

        // Update lock estimate once per block
        if (accCount > 0) {
            double avgSig = sigAcc / accCount; 
            double avgErr = errAcc / accCount; 
            
            double currentEVM = avgErr / (avgSig + 1e-9); // phase error in radians
            
            double alpha = 0.05;
            errMagEMA = (1 - alpha) * errMagEMA + alpha * currentEVM;
            
            // Map EVM to 0-100 lock.
            // EVM = 0 -> 100%
            // EVM > 0.5 -> 0%
            double quality = 1.0 - (errMagEMA / 0.5);
            if (quality < 0) quality = 0;
            if (quality > 1) quality = 1;
            lockPercent = (int) Math.round(quality * 100.0);
        }
    }

    private void processBit(int bit) {
        currentSymbolBits.append(bit == 0 ? '0' : '1');
        int len = currentSymbolBits.length();
        // Cap the buffer to 15 bits. If it gets this big without a separator, drop oldest bit.
        if (len > 15) {
            currentSymbolBits.deleteCharAt(0);
            len--;
        }
        if (len >= 2) {
            if (currentSymbolBits.charAt(len - 1) == '0' && currentSymbolBits.charAt(len - 2) == '0') {
                // Found separator "00"
                String code = currentSymbolBits.substring(0, len - 2);
                if (!code.isEmpty()) {
                    Character c = REVERSE_VARICODE.get(code);
                    if (c != null) {
                        outBuffer.append(c);
                        // Emit on word boundaries or when buffer grows large
                        if (c == ' ' || c == '\n' || outBuffer.length() >= EMIT_CHUNK_SIZE) {
                            emitBuffer();
                        }
                    }
                }
                currentSymbolBits.setLength(0);
            }
        }
    }

    private void emitBuffer() {
        if (outBuffer.length() == 0) return;
        String s = outBuffer.toString();
        outBuffer.setLength(0);
        // Apply lock squelch: only emit when lock is at/above threshold
        if (lockPercent >= lockSquelchPercent) {
            // Analyze for callsigns before delivering text
            analyzeTextForCallsigns(s);
            if (textListener != null) {
                textListener.accept(s);
            }
        }
    }

    private void analyzeTextForCallsigns(String s) {
        if (s == null || s.isEmpty()) return;
        String up = s.toUpperCase();
        // Heuristic 1: Look for "DE <CALL>"
        int idx = up.indexOf(" DE ");
        if (idx != -1) {
            String after = up.substring(idx + 4).trim();
            String[] parts = after.split("\\s+");
            if (parts.length > 0) considerCallCandidate(parts[0].replaceAll("[^A-Z0-9]", ""));
        }
        // Heuristic 2: Trailing callsign followed by K (go ahead)
        if (up.endsWith(" K") || up.endsWith("  K") || up.endsWith(" KK")) {
            // Grab last token before K
            String[] tokens = up.trim().split("\\s+");
            if (tokens.length >= 2) {
                String cand = tokens[tokens.length - 2].replaceAll("[^A-Z0-9]", "");
                considerCallCandidate(cand);
            }
        }
        // Heuristic 3: Regex pattern — need 3 consecutive matches to accept
        Matcher m = CALLSIGN_PATTERN.matcher(up);
        while (m.find()) {
            String cand = m.group();
            considerCallCandidate(cand);
        }
    }

    private void considerCallCandidate(String cand) {
        if (cand == null) return;
        Matcher mm = CALLSIGN_PATTERN.matcher(cand);
        if (!mm.matches()) {
            // Not a valid call pattern
            lastCallCandidate = null;
            callCandidateStreak = 0;
            return;
        }
        if (cand.equals(lastCallCandidate)) {
            callCandidateStreak++;
        } else {
            lastCallCandidate = cand;
            callCandidateStreak = 1;
        }
        if (callCandidateStreak >= 3) {
            // Accept as active station
            if (!cand.equals(activeCallsign)) {
                activeCallsign = cand;
                if (callsignListener != null) {
                    try { callsignListener.accept(activeCallsign); } catch (Exception ignore) {}
                }
            }
        }
    }

    // --- Public controls/metrics ---
    public boolean isAfcEnabled() { return false; }
    public void setAfcEnabled(boolean enabled) { /* AFC removed: no-op */ }

    public int getLockPercent() { return lockPercent; }

    public int getLockSquelchPercent() { return lockSquelchPercent; }
    public void setLockSquelchPercent(int p) {
        if (p < 0) p = 0; if (p > 100) p = 100;
        this.lockSquelchPercent = p;
    }

    // Callsign detection API
    public void setCallsignListener(Consumer<String> listener) { this.callsignListener = listener; }
    public String getActiveCallsign() { return activeCallsign; }
}
