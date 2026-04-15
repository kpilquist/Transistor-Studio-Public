package org.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PSK31Encoder implements SignalEncoder {
    private static final double BAUD_RATE = 31.25;
    private static final int CARRIER_FREQ = 1000; // Default carrier, usually relative to USB dial freq.
    // In this architecture, generateSamples produces audio. The carrier frequency is the audio frequency.
    // The user might want to set this. For now, I'll use a default or pass it in?
    // SignalEncoder interface only takes text and sampleRate.
    // RTTYEncoder has hardcoded frequencies.
    // I'll use a default audio frequency, e.g., 1000 Hz.

    private int carrierFreq = 1000;

    private static final Map<Character, String> VARICODE = new HashMap<>();

    static {
        // Lowercase
        VARICODE.put(' ', "1");
        VARICODE.put('e', "11");
        VARICODE.put('t', "101");
        VARICODE.put('a', "1011");
        VARICODE.put('o', "10111");
        VARICODE.put('i', "1101");
        VARICODE.put('n', "1111");
        VARICODE.put('s', "101111");
        VARICODE.put('h', "10101");
        VARICODE.put('r', "101011");
        VARICODE.put('d', "101101");
        VARICODE.put('l', "11011");
        VARICODE.put('u', "110111");
        VARICODE.put('c', "1011011");
        VARICODE.put('m', "111011");
        VARICODE.put('w', "1101011");
        VARICODE.put('f', "111101");
        VARICODE.put('g', "1111011");
        VARICODE.put('y', "1011101");
        VARICODE.put('p', "1110111");
        VARICODE.put('b', "1011111");
        VARICODE.put('v', "11111101");
        VARICODE.put('k', "10111111");
        VARICODE.put('j', "11110101");
        VARICODE.put('x', "11011111");
        VARICODE.put('q', "110111111");
        VARICODE.put('z', "111010101");

        // Numbers
        VARICODE.put('0', "10110111");
        VARICODE.put('1', "10111101");
        VARICODE.put('2', "11101101");
        VARICODE.put('3', "11111111");
        VARICODE.put('4', "101110111");
        VARICODE.put('5', "101011011");
        VARICODE.put('6', "101101011");
        VARICODE.put('7', "110101101");
        VARICODE.put('8', "110101011");
        VARICODE.put('9', "110110111");

        // Punctuation
        VARICODE.put('.', "1010111");
        VARICODE.put(',', "10111011");
        VARICODE.put('?', "101010111");
        VARICODE.put('-', "101101");
        VARICODE.put('/', "1111101");
        VARICODE.put('\n', "11101"); // CR/LF

        // Uppercase
        VARICODE.put('A', "11111011");
        VARICODE.put('B', "11101011");
        VARICODE.put('C', "10101101");
        VARICODE.put('D', "10110101");
        VARICODE.put('E', "1110111");
        VARICODE.put('F', "11011011");
        VARICODE.put('G', "11111101");
        VARICODE.put('H', "101010101");
        VARICODE.put('I', "1111111");
        VARICODE.put('J', "111111101");
        VARICODE.put('K', "101111101");
        VARICODE.put('L', "11010111");
        VARICODE.put('M', "10111011");
        VARICODE.put('N', "11011101");
        VARICODE.put('O', "10101011");
        VARICODE.put('P', "1110101");
        VARICODE.put('Q', "111111111");
        VARICODE.put('R', "10101111");
        VARICODE.put('S', "1101111");
        VARICODE.put('T', "1101101");
        VARICODE.put('U', "10101011");
        VARICODE.put('V', "110110101");
        VARICODE.put('W', "101011101");
        VARICODE.put('X', "101110101");
        VARICODE.put('Y', "101111011");
        VARICODE.put('Z', "1010101101");
    }

    public void setCarrierFrequency(int freq) {
        this.carrierFreq = freq;
    }

    /**
     * Generates a continuous PSK31 idle (sequence of 0-bits causing phase reversals)
     * for approximately the requested duration. Useful for tune/sync.
     */
    public float[] generateIdleSamples(int durationMs, int sampleRate) {
        if (durationMs <= 0) durationMs = 250;
        int totalSamples = (int) Math.max(1, Math.round(durationMs * sampleRate / 1000.0));
        double samplesPerBit = sampleRate / BAUD_RATE;
        int totalBits = (int) Math.ceil(totalSamples / samplesPerBit);
        List<Float> out = new ArrayList<>(totalSamples);
        double time = 0.0;
        double currentAmplitude = 1.0;
        for (int i = 0; i < totalBits; i++) {
            for (int s = 0; s < samplesPerBit && out.size() < totalSamples; s++) {
                double tRel = (double) s / samplesPerBit;
                double envelope = currentAmplitude * Math.cos(Math.PI * tRel); // reversal over symbol
                double sampleVal = envelope * Math.cos(2 * Math.PI * carrierFreq * time);
                out.add((float) sampleVal);
                time += 1.0 / sampleRate;
            }
            currentAmplitude = -currentAmplitude; // next symbol reverses again
        }
        float[] arr = new float[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    @Override
    public float[] generateSamples(String text, int sampleRate) {
        List<Float> sampleList = new ArrayList<>();
        double samplesPerBit = sampleRate / BAUD_RATE;

        // Preamble: Continuous zeros (phase reversals) for sync?
        // Usually PSK31 starts with a preamble of zeros (reversals) to help receiver sync.
        // Let's send some zeros.
        String preamble = "0000000000000000";

        StringBuilder bitStream = new StringBuilder();
        bitStream.append(preamble);

        for (char c : text.toCharArray()) {
            String code = VARICODE.get(c);
            if (code == null) {
                // Try uppercase/lowercase fallback or ignore
                if (Character.isUpperCase(c)) {
                    code = VARICODE.get(Character.toLowerCase(c));
                } else if (Character.isLowerCase(c)) {
                    code = VARICODE.get(Character.toUpperCase(c));
                }
            }

            if (code != null) {
                bitStream.append(code);
                bitStream.append("00"); // Character separator
            }
        }

        // Postamble
        bitStream.append("11111"); // Steady carrier to finish

        double currentPhase = 0;
        double time = 0;

        // We need to process the bitstream.
        // 0 = Phase Reversal
        // 1 = No Phase Reversal

        // We process bit by bit.
        // For each bit, we generate 'samplesPerBit' samples.
        // If the bit is 0, we need to transition phase.
        // If the bit is 1, we keep phase.

        // However, the transition happens AT the boundary.
        // And the amplitude shaping happens around the boundary?
        // Actually, for PSK31, the amplitude envelope is cos(pi * t / T_symbol) ?
        // No, it's simpler to think of it as:
        // If bit is 0 (reversal), the amplitude goes 1 -> 0 -> 1 (with sign flip).
        // If bit is 1 (steady), the amplitude stays 1.

        // Let's model it as:
        // Signal = Envelope(t) * cos(2*pi*f*t + Phase)
        // But Phase changes.

        // Better model:
        // Signal = I(t) * cos(wt) - Q(t) * sin(wt)
        // For BPSK, Q(t) = 0.
        // Signal = I(t) * cos(wt).
        // I(t) takes values +1 and -1.
        // When I(t) changes from +1 to -1 or vice versa, it follows a cosine path.
        // I(t) = cos(pi * t / T_symbol) during the transition?

        // Correct implementation of PSK31 shaping:
        // The signal is A(t) * cos(2*pi*f*t).
        // A(t) represents the data.
        // If we send a '0' (reversal), A(t) changes sign.
        // If we send a '1' (steady), A(t) stays same sign.
        // The transition is shaped.

        // Let's assume we are at the center of a symbol.
        // We have a current amplitude level (1 or -1).
        // We look at the next bit (0 or 1).
        // If 0: We need to flip sign. We use a cosine transition.
        // If 1: We stay. We use constant amplitude.

        // Wait, Varicode 0 means reversal.
        // So if bit is 0, we flip.

        double currentAmplitude = 1.0; // Start with +1

        for (int i = 0; i < bitStream.length(); i++) {
            char bit = bitStream.charAt(i);
            boolean reversal = (bit == '0');

            double targetAmplitude = reversal ? -currentAmplitude : currentAmplitude;

            // Generate samples for this symbol period
            for (int s = 0; s < samplesPerBit; s++) {
                double tRel = (double)s / samplesPerBit; // 0 to 1

                // Amplitude envelope
                double envelope;
                if (reversal) {
                    // Cosine shape from current to target
                    // We want to go from 1 to -1 (or -1 to 1)
                    // shape = cos(pi/2 + pi * tRel)? No.
                    // We want to go through zero at tRel=0.5?
                    // Standard PSK31 uses a full cosine cycle for the bit?
                    // "The envelope is a cosine function: A(t) = cos(pi * t / T)"
                    // This implies A(t) goes from 1 to -1 (or -1 to 1) over the symbol period T.
                    // This matches "0 = reversal".

                    // If bit is 1 (no reversal), A(t) = 1 (or -1) constant?
                    // "When a continuous sequence of ones is sent, the amplitude is constant."

                    // So:
                    // If reversal: Envelope follows cos(pi * tRel) * currentAmplitude?
                    // At tRel=0, cos(0)=1 -> currentAmplitude.
                    // At tRel=1, cos(pi)=-1 -> -currentAmplitude.
                    // This works!
                    envelope = currentAmplitude * Math.cos(Math.PI * tRel);
                } else {
                    // No reversal. Constant amplitude.
                    envelope = currentAmplitude;
                }

                double sampleVal = envelope * Math.cos(2 * Math.PI * carrierFreq * time);
                sampleList.add((float)sampleVal);

                time += 1.0 / sampleRate;
            }

            currentAmplitude = targetAmplitude;
        }

        float[] result = new float[sampleList.size()];
        for (int i = 0; i < sampleList.size(); i++) {
            result[i] = sampleList.get(i);
        }
        return result;
    }
}

