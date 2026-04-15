package org.example;

import java.util.function.Consumer;

public interface SignalDecoder {
    /**
     * Process a chunk of audio samples.
     * @param samples The raw audio samples (normalized -1.0 to 1.0).
     * @param sampleRate The sample rate of the audio.
     */
    void processSamples(float[] samples, int sampleRate);

    /**
     * Sets the listener for decoded text.
     * @param listener Consumer that accepts decoded strings.
     */
    void setDecodedTextListener(Consumer<String> listener);

    /**
     * Sets the center frequency of the signal to decode.
     * @param frequency Frequency in Hz.
     */
    void setTargetFrequency(int frequency);

    /**
     * Optional: set the decoder's internal bandpass width in Hz, if supported.
     * Default is a no-op for decoders that don't use a bandpass filter.
     */
    default void setBandpassWidthHz(double widthHz) {
        // no-op by default
    }
}

