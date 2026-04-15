package org.example;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optimized for ATAS-120 / Yaesu CAT Control integrated with HEAM safety.
 *
 * Responsibilities:
 * - Observe SWR from CAT and mirror it into {@link SwrMonitor}.
 * - When SWR exceeds a conservative threshold during HEAM operation, trigger ATAS Tune via {@link RadioControl}.
 * - While the radio is tuning, inhibit HEAM transmission using {@link SwrMonitor.HighSwrPolicy#INHIBIT_TX}.
 * - When tuning completes, restore the default DOWN_SHIFT policy so HEAM can resume at safe mode.
 */
public class AtasController {
    private static final AtasController INSTANCE = new AtasController();

    public static AtasController getInstance() { return INSTANCE; }

    private volatile RadioControl radio;
    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final AtomicBoolean tuning = new AtomicBoolean(false);

    // Conservative trigger for initiating ATAS tune in HEAM context
    private volatile double triggerSwr = 1.5; // User's requirement

    // Map Yaesu RM SWR units to approximate SWR (heuristic if radio doesn't give absolute SWR)
    // Many Yaesu rigs report RM4 0..255 scale; as a fallback, value/100 gives a reasonable 1.0..2.5 range.
    private static double mapRmToSwr(int rmValue) {
        // Ensure at least 1.0
        double swr = Math.max(1.0, rmValue / 100.0);
        // Cap at something sane
        if (swr > 10.0) swr = 10.0;
        return swr;
    }

    private AtasController() {}

    public void setEnabled(boolean on) {
        enabled.set(on);
    }

    public boolean isEnabled() { return enabled.get(); }

    public void setTriggerSwr(double v) {
        if (v >= 1.0 && v < 10.0) triggerSwr = v;
    }

    public void setRadioControl(RadioControl rc) {
        this.radio = rc;
        if (rc == null) return;
        try {
            // Mirror SWR readings into SwrMonitor singleton for HEAM engine decisions
            rc.setSWRListener(val -> {
                try {
                    double swr = mapRmToSwr(val);
                    SwrMonitor.getInstance().setCurrentSwr(swr);
                } catch (Throwable ignore) {}
            });
        } catch (Throwable ignore) {}
        try {
            // Observe tuning state to apply TX inhibit during tune
            rc.setTuningStatusListener(isTuning -> {
                tuning.set(Boolean.TRUE.equals(isTuning));
                if (Boolean.TRUE.equals(isTuning)) {
                    // Block HEAM transmissions during active ATAS tune
                    SwrMonitor.getInstance().setPolicy(SwrMonitor.HighSwrPolicy.INHIBIT_TX);
                } else {
                    // Allow HEAM to resume with downshift safety after tune completes
                    SwrMonitor.getInstance().setPolicy(SwrMonitor.HighSwrPolicy.DOWN_SHIFT);
                }
            });
        } catch (Throwable ignore) {}
    }

    /**
     * Call this before initiating a HEAM transmission. If SWR is above the
     * threshold and ATAS integration is enabled, we request the radio to tune
     * and inhibit HEAM TX until the radio reports tuning complete.
     */
    public void ensureResonance() {
        if (!enabled.get()) return;
        RadioControl rc = this.radio;
        if (rc == null || !rc.isConnected()) return;

        double current = SwrMonitor.getInstance().getCurrentSwr();
        if (current > triggerSwr && !tuning.get()) {
            System.out.println("HEAM: ATAS-120 out of resonance (SWR=" + String.format("%.2f", current) + "). Sending CAT Tune Command...");
            try {
                // Inhibit HEAM TX immediately
                SwrMonitor.getInstance().setPolicy(SwrMonitor.HighSwrPolicy.INHIBIT_TX);
                tuning.set(true);
                rc.setATASTune(true);
                // Note: RadioControl will call our tuningStatusListener when tuning state changes
            } catch (Throwable t) {
                // If CAT fails, fall back to DOWN_SHIFT so at least robust mode can be used if policy permits
                tuning.set(false);
                SwrMonitor.getInstance().setPolicy(SwrMonitor.HighSwrPolicy.DOWN_SHIFT);
            }
        }
    }
}
