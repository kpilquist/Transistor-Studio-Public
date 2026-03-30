package org.example;

import java.util.ArrayList;
import java.util.List;

public class RTTYEncoder implements SignalEncoder {
    private int markFreq = 2125;
    private int spaceFreq = 2295; // Standard amateur RTTY (Mark low, Space high? No, usually Mark=2125, Space=2295 for AFSK LSB, or reversed)
    // Actually, standard RTTY is Mark=2125, Space=2295 (170Hz shift).
    // But in LSB, higher audio freq = lower RF freq.
    // Let's stick to 2125/2295.

    private double baudRate = 45.45;

    public void setFrequencies(int markHz, int spaceHz) {
        this.markFreq = Math.max(1, markHz);
        this.spaceFreq = Math.max(1, spaceHz);
    }

    public void setBaudRate(double baud) {
        if (baud > 0) this.baudRate = baud;
    }

    public int getMarkFreq() { return markFreq; }
    public int getSpaceFreq() { return spaceFreq; }
    public double getBaudRate() { return baudRate; }

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

    private double currentPhase = 0;

    @Override
    public float[] generateSamples(String text, int sampleRate) {
        List<Float> sampleList = new ArrayList<>();
        double samplesPerBit = sampleRate / baudRate;
        currentPhase = 0;

        boolean figuresMode = false;

        // Preamble (Idle Mark)
        generateTone(sampleList, markFreq, samplesPerBit * 5, sampleRate); // 5 bits of mark

        for (char c : text.toUpperCase().toCharArray()) {
            int code = -1;
            boolean needFigures = false;
            boolean needLetters = false;

            // Search in LETTERS
            for (int i = 0; i < LETTERS.length; i++) {
                if (LETTERS[i] == c) {
                    code = i;
                    if (figuresMode) needLetters = true;
                    break;
                }
            }

            // Search in FIGURES
            if (code == -1) {
                for (int i = 0; i < FIGURES.length; i++) {
                    if (FIGURES[i] == c) {
                        code = i;
                        if (!figuresMode) needFigures = true;
                        break;
                    }
                }
            }

            if (code != -1) {
                if (needFigures) {
                    encodeByte(0x1B, sampleList, samplesPerBit, sampleRate); // FIGS
                    figuresMode = true;
                } else if (needLetters) {
                    encodeByte(0x1F, sampleList, samplesPerBit, sampleRate); // LTRS
                    figuresMode = false;
                }
                encodeByte(code, sampleList, samplesPerBit, sampleRate);
            }
        }

        // Postamble (Idle Mark)
        encodeBit(true, sampleList, samplesPerBit * 2, sampleRate); // Stop bits

        // Convert to array
        float[] result = new float[sampleList.size()];
        for (int i = 0; i < sampleList.size(); i++) {
            result[i] = sampleList.get(i);
        }
        return result;
    }

    private void encodeByte(int code, List<Float> sampleList, double samplesPerBit, int sampleRate) {
        // Start bit (Space / 0)
        encodeBit(false, sampleList, samplesPerBit, sampleRate);

        // 5 Data bits (LSB first)
        for (int i = 0; i < 5; i++) {
            boolean bit = ((code >> i) & 1) == 1;
            encodeBit(bit, sampleList, samplesPerBit, sampleRate);
        }

        // Stop bits (Mark / 1) - 1.5 bits usually
        encodeBit(true, sampleList, samplesPerBit * 1.5, sampleRate);
    }

    private void encodeBit(boolean bit, List<Float> sampleList, double durationSamples, int sampleRate) {
        int freq = bit ? markFreq : spaceFreq;
        generateTone(sampleList, freq, durationSamples, sampleRate);
    }

    private void generateTone(List<Float> sampleList, int freq, double durationSamples, int sampleRate) {
        for (int i = 0; i < durationSamples; i++) {
            float sample = (float) Math.sin(currentPhase);
            sampleList.add(sample);
            currentPhase += 2 * Math.PI * freq / sampleRate;
            if (currentPhase > 2 * Math.PI) currentPhase -= 2 * Math.PI;
        }
    }
}
