package org.example;

/**
 * Optional capability interface for spectrogram displays that can expose
 * the most recent spectrum snapshot to other UI components (e.g., Radar).
 *
 * Implementations should return a read-only snapshot array; callers must treat
 * it as immutable.
 */
public interface SpectrumSnapshotProvider {
    /**
     * Returns the most recent spectrum snapshot, or null if none yet.
     */
    double[] getLatestSpectrum();

    /**
     * Returns the number of bins in the latest spectrum snapshot (0 if none).
     */
    int getLatestSpectrumBins();
}
