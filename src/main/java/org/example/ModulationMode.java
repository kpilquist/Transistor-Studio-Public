package org.example;

/**
 * Minimal modulation modes previously provided by HEAM engine.
 * Kept as a standalone enum so components like SwrMonitor/ATAS can compile
 * without pulling in the full HEAM implementation.
 */
public enum ModulationMode {
    QAM64_TURBO,
    QPSK_STABLE,
    BPSK_ROBUST
}
