package org.example;

public interface SignalEncoder {
    /**
     * Generates audio samples for the given text.
     * @param text The text to encode.
     * @param sampleRate The target sample rate.
     * @return Array of float samples (-1.0 to 1.0).
     */
    float[] generateSamples(String text, int sampleRate);
}

