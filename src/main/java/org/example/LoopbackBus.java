package org.example;

/**
 * Minimal no-op stub preserved for compatibility after removing HEAM.
 * Provides a virtual loopback device name and a sink for sample writes.
 */
public final class LoopbackBus {
    public static final String LOOPBACK_DEVICE_NAME = "Loopback";

    private static final LoopbackBus INSTANCE = new LoopbackBus();

    public static LoopbackBus getInstance() {
        return INSTANCE;
    }

    private LoopbackBus() {}

    /**
     * Accepts audio samples and discards them. Present to avoid breaking callers.
     */
    public void write(double[] samples) {
        // no-op
    }

    /**
     * Overload to accept float PCM arrays without forcing callers to convert.
     */
    public void write(float[] samples) {
        // no-op
    }
}
