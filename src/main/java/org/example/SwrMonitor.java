package org.example;

/**
 * Monitors Standing Wave Ratio (SWR) to protect the transmitter and finals.
 * Provides a safety interlock for HEAM transmissions.
 */
public class SwrMonitor {

    public enum HighSwrPolicy {
        DOWN_SHIFT,   // downshift modulation to a safer mode
        INHIBIT_TX    // block transmission by throwing HardwareException
    }

    private static final SwrMonitor INSTANCE = new SwrMonitor();

    // Current SWR reading (can be updated by radio control / CAT / user simulation)
    private volatile double currentSwr = 1.0;

    // Thresholds
    private volatile double warnSwr = 1.8;   // start clamping to QPSK_STABLE when exceeded
    private volatile double maxSwr = 2.2;    // above this: either BPSK_ROBUST or inhibit TX

    private volatile HighSwrPolicy policy = HighSwrPolicy.DOWN_SHIFT;

    private SwrMonitor() {}

    public static SwrMonitor getInstance() { return INSTANCE; }

    public double getCurrentSwr() { return currentSwr; }
    public void setCurrentSwr(double swr) { this.currentSwr = Math.max(1.0, swr); }

    public double getWarnSwr() { return warnSwr; }
    public void setWarnSwr(double warnSwr) { this.warnSwr = warnSwr; }

    public double getMaxSwr() { return maxSwr; }
    public void setMaxSwr(double maxSwr) { this.maxSwr = maxSwr; }

    public HighSwrPolicy getPolicy() { return policy; }
    public void setPolicy(HighSwrPolicy p) { if (p != null) this.policy = p; }

    /**
     * @return true if we can transmit safely at all (<= maxSwr)
     */
    public boolean canTransmit() { return currentSwr <= maxSwr; }

    /**
     * Returns the maximum allowed modulation mode under the current SWR.
     * - If SWR <= warnSwr: allow any mode (QAM64_TURBO)
     * - If warnSwr < SWR <= maxSwr: clamp to QPSK_STABLE
     * - If SWR > maxSwr: depending on policy, either BPSK_ROBUST (DOWN_SHIFT) or null (INHIBIT_TX)
     */
    public ModulationMode getMaxAllowedMode() {
        if (currentSwr <= warnSwr) {
            return ModulationMode.QAM64_TURBO;
        } else if (currentSwr <= maxSwr) {
            return ModulationMode.QPSK_STABLE;
        } else {
            if (policy == HighSwrPolicy.DOWN_SHIFT) {
                return ModulationMode.BPSK_ROBUST;
            }
            return null; // INHIBIT_TX
        }
    }
}
