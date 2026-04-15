package org.example;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * License-based band plan helper for transmit boundaries.
 *
 * Usage:
 *  - Digital:  BandPlan.isDigitalTxAllowed(license, freqHz, modeName)
 *  - Generic:  BandPlan.isTxAllowed(license, freqHz, category, modeName)
 *
 * Notes:
 * - Defaults to US allocations. VHF/UHF are broadly available to all licenses; HF varies by class.
 * - 60m is channelized in the U.S.; we accept small windows around 5 channel centers and allow USB voice & data.
 * - 30m has no phone/voice; CW and digital/data only (power limits are informational only here).
 * - OFF license disables enforcement.
 */
public final class BandPlan {

    public enum License {
        OFF,
        TECHNICIAN,
        GENERAL,
        EXTRA
    }

    public enum ModeCategory {
        VOICE,    // LSB/USB/AM/FM, phone
        DIGITAL,  // DATA-U/L, RTTY, PSK, VARA, SSTV, etc.
        CW        // CW-L/U (Morse)
    }

    // Public view type for UI overlays
    public static final class Segment {
        public final long startHz;
        public final long endHz;
        public final EnumSet<ModeCategory> categories;
        public final String bandLabel;
        public final String notes;
        public Segment(long startHz, long endHz, EnumSet<ModeCategory> categories, String bandLabel, String notes) {
            this.startHz = startHz;
            this.endHz = endHz;
            this.categories = categories;
            this.bandLabel = bandLabel;
            this.notes = notes;
        }
        @Override public String toString() {
            String cat = categories.toString();
            String label = (bandLabel != null ? (bandLabel + ": ") : "");
            String n = (notes != null && !notes.isEmpty()) ? (" — " + notes) : "";
            return String.format("%s%s - %s %s%s", label, human(startHz), human(endHz), cat, n);
        }
    }

    private static final class Range {
        final long startHz;
        final long endHz;
        final EnumSet<ModeCategory> categories; // which categories are permitted in this segment
        final String bandLabel; // e.g., "20m"
        final String notes;     // e.g., "CW only", "60m channelized"
        Range(long startHz, long endHz, EnumSet<ModeCategory> categories, String bandLabel, String notes) {
            this.startHz = startHz;
            this.endHz = endHz;
            this.categories = categories;
            this.bandLabel = bandLabel;
            this.notes = notes;
        }
        boolean contains(long f) { return f >= startHz && f <= endHz; }
        @Override public String toString() {
            String cat = categories.toString();
            String label = (bandLabel != null ? (bandLabel + ": ") : "");
            String n = (notes != null && !notes.isEmpty()) ? (" — " + notes) : "";
            return String.format("%s%s - %s %s%s", label, human(startHz), human(endHz), cat, n);
        }
    }

    // Precomputed channel ranges for 60m (center +/- 1.5 kHz). Voice + Digital allowed.
    private static final List<Range> RANGES_60M;
    static {
        List<Range> ch = new ArrayList<>();
        double[] centersKHz = {5330.5, 5346.5, 5357.0, 5371.5, 5403.5};
        for (double c : centersKHz) {
            long center = (long) Math.round(c * 1000.0);
            long start = (center - 1500);
            long end = (center + 1500);
            ch.add(new Range(start, end, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.VOICE, ModeCategory.CW), "60m", "Channelized (USB voice & data)"));
        }
        RANGES_60M = Collections.unmodifiableList(ch);
    }

    private static Range rangeMHz(double startMHz, double endMHz, EnumSet<ModeCategory> cats, String bandLabel, String notes) {
        return new Range((long) Math.round(startMHz * 1_000_000.0), (long) Math.round(endMHz * 1_000_000.0), cats, bandLabel, notes);
    }

    private static List<Range> rangesForUS(License lic) {
        List<Range> r = new ArrayList<>();
        if (lic == License.OFF) return r; // disabled

        // HF bands — DIGITAL segments (conservative per earlier table)
        switch (lic) {
            case EXTRA:
                r.add(rangeMHz(1.800, 1.900, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "160m", ""));
                r.add(rangeMHz(3.500, 3.600, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "80m", ""));
                r.addAll(RANGES_60M); // 60m — allow voice+data+cw
                r.add(rangeMHz(7.000, 7.125, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "40m", ""));
                r.add(rangeMHz(10.100, 10.150, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "30m", "No phone"));
                r.add(rangeMHz(14.000, 14.150, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "20m", ""));
                r.add(rangeMHz(18.068, 18.110, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "17m", ""));
                r.add(rangeMHz(21.000, 21.200, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "15m", ""));
                r.add(rangeMHz(24.890, 24.930, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "12m", ""));
                r.add(rangeMHz(28.000, 28.300, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "10m", ""));
                // Phone/VOICE HF
                r.add(rangeMHz(1.900, 2.000, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "160m", "Phone"));
                r.add(rangeMHz(3.600, 4.000, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "80/75m", "Phone"));
                r.add(rangeMHz(7.125, 7.300, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "40m", "Phone"));
                r.add(rangeMHz(14.150, 14.350, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "20m", "Phone"));
                r.add(rangeMHz(18.110, 18.168, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "17m", "Phone"));
                r.add(rangeMHz(21.200, 21.450, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "15m", "Phone"));
                r.add(rangeMHz(24.930, 24.990, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "12m", "Phone"));
                r.add(rangeMHz(28.300, 29.700, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "10m", "Phone"));
                break;
            case GENERAL:
                r.add(rangeMHz(1.800, 1.900, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "160m", ""));
                r.add(rangeMHz(3.525, 3.600, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "80m", ""));
                r.addAll(RANGES_60M);
                r.add(rangeMHz(7.025, 7.125, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "40m", ""));
                r.add(rangeMHz(10.100, 10.150, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "30m", "No phone"));
                r.add(rangeMHz(14.025, 14.150, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "20m", ""));
                r.add(rangeMHz(18.068, 18.110, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "17m", ""));
                r.add(rangeMHz(21.025, 21.200, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "15m", ""));
                r.add(rangeMHz(24.890, 24.930, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "12m", ""));
                r.add(rangeMHz(28.000, 28.300, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "10m", ""));
                // Phone
                r.add(rangeMHz(1.900, 2.000, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "160m", "Phone"));
                r.add(rangeMHz(3.800, 4.000, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "80/75m", "Phone"));
                r.add(rangeMHz(7.175, 7.300, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "40m", "Phone"));
                r.add(rangeMHz(14.225, 14.350, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "20m", "Phone"));
                r.add(rangeMHz(18.110, 18.168, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "17m", "Phone"));
                r.add(rangeMHz(21.275, 21.450, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "15m", "Phone"));
                r.add(rangeMHz(24.930, 24.990, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "12m", "Phone"));
                r.add(rangeMHz(28.300, 29.700, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "10m", "Phone"));
                break;
            case TECHNICIAN:
                // HF digital/CW privileges
                r.add(rangeMHz(3.525, 3.600, EnumSet.of(ModeCategory.CW), "80m", "CW only"));
                r.add(rangeMHz(7.025, 7.125, EnumSet.of(ModeCategory.CW), "40m", "CW only"));
                r.add(rangeMHz(21.025, 21.100, EnumSet.of(ModeCategory.CW), "15m", "CW only"));
                r.add(rangeMHz(28.000, 28.300, EnumSet.of(ModeCategory.DIGITAL, ModeCategory.CW), "10m", "Data/CW"));
                // Phone on 10 m
                r.add(rangeMHz(28.300, 28.500, EnumSet.of(ModeCategory.VOICE, ModeCategory.CW), "10m", "Phone"));
                break;
        }

        // Above 30 MHz (VHF/UHF/SHF) — generally available to all license classes in US
        // We add broad allocations and allow both VOICE and DIGITAL and CW.
        EnumSet<ModeCategory> ALL = EnumSet.of(ModeCategory.VOICE, ModeCategory.DIGITAL, ModeCategory.CW);
        r.add(rangeMHz(50.000, 54.000, ALL, "6m", ""));
        r.add(rangeMHz(144.000, 148.000, ALL, "2m", ""));
        r.add(rangeMHz(222.000, 225.000, ALL, "1.25m", ""));
        r.add(rangeMHz(420.000, 450.000, ALL, "70cm", ""));
        r.add(rangeMHz(902.000, 928.000, ALL, "33cm", ""));
        r.add(rangeMHz(1240.000, 1300.000, ALL, "23cm", ""));
        return r;
    }

    public static boolean isTxAllowed(License license, long freqHz, ModeCategory category, String modeName) {
        if (license == null || license == License.OFF) return true;
        List<Range> ranges = rangesForUS(license);
        for (Range rg : ranges) {
            if (rg.contains(freqHz) && rg.categories.contains(category)) {
                // 30m note is informational only; category logic already handles Phone exclusion
                return true;
            }
        }
        return false;
    }

    // Backward compatibility for existing digital checks
    public static boolean isDigitalTxAllowed(License license, long freqHz, String modeName) {
        return isTxAllowed(license, freqHz, ModeCategory.DIGITAL, modeName);
    }

    public static String allowedRangesSummary(License license) {
        if (license == null || license == License.OFF) return "Enforcement is OFF";
        StringBuilder sb = new StringBuilder();
        for (Range rg : rangesForUS(license)) {
            if (rg.categories.contains(ModeCategory.DIGITAL)) {
                sb.append("• ").append(rg.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    public static String allowedRangesSummary(License license, ModeCategory category) {
        if (license == null || license == License.OFF) return "Enforcement is OFF";
        StringBuilder sb = new StringBuilder();
        for (Range rg : rangesForUS(license)) {
            if (rg.categories.contains(category)) {
                sb.append("• ").append(rg.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    public static String fullSpectrumSummary(License license) {
        if (license == null || license == License.OFF) return "Enforcement is OFF";
        StringBuilder sb = new StringBuilder();
        for (Range rg : rangesForUS(license)) {
            sb.append("• ").append(rg.toString()).append("\n");
        }
        return sb.toString();
    }

    // --- Public accessors for UI overlays ---
    public static List<Segment> segmentsFor(License license) {
        List<Segment> out = new ArrayList<>();
        if (license == null || license == License.OFF) return out;
        for (Range rg : rangesForUS(license)) {
            out.add(new Segment(rg.startHz, rg.endHz, rg.categories, rg.bandLabel, rg.notes));
        }
        return out;
    }

    public static List<Segment> segmentsFor(License license, ModeCategory category) {
        List<Segment> out = new ArrayList<>();
        if (license == null || license == License.OFF) return out;
        for (Range rg : rangesForUS(license)) {
            if (rg.categories.contains(category)) {
                out.add(new Segment(rg.startHz, rg.endHz, rg.categories, rg.bandLabel, rg.notes));
            }
        }
        return out;
    }

    private static String human(long hz) {
        if (hz >= 1_000_000) {
            return String.format("%.3f MHz", hz / 1_000_000.0);
        } else if (hz >= 1000) {
            return String.format("%.1f kHz", hz / 1000.0);
        } else {
            return hz + " Hz";
        }
    }
}
