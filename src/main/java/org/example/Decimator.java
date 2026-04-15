package org.example;

/**
 * Helper class to downsample audio data.
 * Reduces sample rate by an integer factor, allowing for efficient
 * processing of narrow bandwidths (e.g., 3kHz) from wideband sources (48kHz).
 */
public class Decimator {
    private final int factor;
    private final float[] kernel;
    private final float[] delayLine;
    private int delayIndex = 0;

    /**
     * @param factor The downsampling factor (e.g., 8 for 48kHz -> 6kHz)
     */
    public Decimator(int factor) {
        this.factor = factor;
        // Design a FIR Low-pass filter (Windowed Sinc)
        // Cutoff frequency is 0.5 / factor (Nyquist of the new sample rate)
        // We use slightly higher cutoff to avoid rolling off too early in the passband
        // Taps length heuristic: factor * 8 for decent roll-off
        int taps = factor * 8 + 1; // Ensure odd number of taps for symmetry
        // Cap taps to avoid excessive CPU and cache pressure at high decimation factors
        if (taps > 257) taps = 257;

        this.kernel = designLowPassKernel(taps, 1.0f / factor);
        this.delayLine = new float[taps];
    }

    /**
     * Processes the input buffer and returns a downsampled buffer.
     * @param input The high-sample-rate audio buffer
     * @return The downsampled audio buffer
     */
    public float[] process(float[] input) {
        // Output size is roughly input length / factor
        int outputLen = input.length / factor;
        float[] output = new float[outputLen];
        int outIdx = 0;

        for (int i = 0; i < input.length; i++) {
            // Insert new sample into circular delay line
            delayLine[delayIndex] = input[i];

            // Perform convolution and decimation
            // We only calculate the filter output every 'factor' samples
            if (i % factor == 0 && outIdx < outputLen) {
                float sum = 0;
                for (int k = 0; k < kernel.length; k++) {
                    // Circular buffer read
                    int idx = (delayIndex - k + delayLine.length) % delayLine.length;
                    sum += delayLine[idx] * kernel[k];
                }
                output[outIdx++] = sum;
            }

            // Advance circular buffer index
            delayIndex = (delayIndex + 1) % delayLine.length;
        }
        return output;
    }

    private float[] designLowPassKernel(int taps, float cutoff) {
        float[] h = new float[taps];
        int center = taps / 2;
        for (int i = 0; i < taps; i++) {
            // Sinc function
            if (i == center) {
                h[i] = 2 * cutoff;
            } else {
                float x = (float) (Math.PI * (i - center));
                h[i] = (float) (Math.sin(2 * Math.PI * cutoff * (i - center)) / x);
            }
            // Apply Hamming window to reduce spectral leakage
            h[i] *= (float) (0.54 - 0.46 * Math.cos(2 * Math.PI * i / (taps - 1)));
        }
        return h;
    }
}

