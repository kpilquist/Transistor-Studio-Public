package org.example;

import java.util.function.Consumer;

public class JRadio implements SignalDecoder, SignalEncoder {
    private Consumer<String> textListener;
    private int targetFrequency = 1000;
    private String mode;

    public JRadio(String mode) {
        this.mode = mode;
    }

    @Override
    public void processSamples(float[] samples, int sampleRate) {
        // TODO: Integrate with JRadio library (snigelpa/jradio)
        // The library typically works with IQ streams or specific demodulators.
        // We need to adapt the float[] audio samples to what the library expects.

        // For now, we just log the mode being used.
        // System.out.println("Processing " + mode + " samples...");

        // Simple energy detection placeholder
        float energy = 0;
        for (float sample : samples) {
            energy += sample * sample;
        }
        energy /= samples.length;

        if (energy > 0.01 && textListener != null) {
             // Signal detected
             // textListener.accept("Signal Detected (" + mode + ")");
             // Placeholder for actual decoding
        }
    }


    @Override
    public void setDecodedTextListener(Consumer<String> listener) {
        this.textListener = listener;
    }

    @Override
    public void setTargetFrequency(int frequency) {
        this.targetFrequency = frequency;
    }

    @Override
    public float[] generateSamples(String text, int sampleRate) {
        // TODO: Implement encoding for different modes using JRadio library
        // For now, generate a simple tone sequence for testing

        // 100ms per character
        int samplesPerChar = (int) (0.1 * sampleRate);
        int totalSamples = text.length() * samplesPerChar;
        float[] samples = new float[totalSamples];

        for (int i = 0; i < totalSamples; i++) {
            // Modulate frequency slightly based on character index to make it sound interesting
            int charIndex = i / samplesPerChar;
            int charValue = text.charAt(charIndex);
            double freq = targetFrequency + (charValue % 10) * 50;

            if (mode.equals("CW")) {
                // Simple CW simulation (very rough)
                freq = targetFrequency;
                // On/Off keying logic would be needed here
            }

            samples[i] = (float) Math.sin(2 * Math.PI * freq * i / sampleRate);
        }
        return samples;
    }
}

