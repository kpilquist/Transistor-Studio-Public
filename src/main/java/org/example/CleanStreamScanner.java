package org.example;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CleanStreamScanner scans an incoming text stream (e.g., from PSK31/RTTY decoders)
 * for amateur radio callsigns around common anchors like "DE ", "CQ ", or "UR ".
 *
 * Usage:
 *   CleanStreamScanner scanner = new CleanStreamScanner(call -> { /* UI hook */ /* });
 *   decoder.setDecodedTextListener(scanner::addText); // feed decoded chunks
 *
 * Behavior:
 * - Maintains a small sliding window buffer (~128 chars) of the latest sanitized text.
 * - When an anchor is detected, attempts to extract a callsign using CALLSIGN_PATTERN
 *   from the text immediately following the anchor.
 * - Validates against an internal false-positive list to avoid tagging hardware/terms.
 * - Notifies via callsignFoundListener when a callsign is identified.
 */
public class CleanStreamScanner {
    /** Pattern for typical amateur callsigns with optional suffix like /P, /QRP, /MM, /M, /<area> */
    public static final Pattern CALLSIGN_PATTERN = Pattern.compile(
            "\\b(?:[A-Z]{1,3}\\d[A-Z]{1,4})(?:/[A-Z0-9]{1,4})?\\b");

    private static final String[] DEFAULT_ANCHORS = {" DE ", " CQ ", " UR ", " DE", " CQ", " UR"};

    private final StringBuilder buffer = new StringBuilder(128);
    private int windowSize = 128;

    private final Set<String> falsePositives = new HashSet<>();
    private Consumer<String> callsignFoundListener;

    // Simple de-duplication so we don't spam the same call repeatedly
    private String lastEmitted = null;

    // Limit how far after the anchor we search for a callsign
    private int searchSpanAfterAnchor = 24; // characters

    public CleanStreamScanner(Consumer<String> callsignFoundListener) {
        this.callsignFoundListener = callsignFoundListener;
        loadDefaultFalsePositives();
    }

    public CleanStreamScanner() {
        loadDefaultFalsePositives();
    }

    /**
     * Feed new decoded text into the scanner. This should be raw decoded text; the
     * scanner will sanitize and uppercase it internally.
     */
    public void addText(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        String clean = sanitize(chunk);
        if (clean.isEmpty()) return;
        buffer.append(clean);
        trimToWindow();
        scanForCallsign();
    }

    /** Set or replace the listener that is notified when a callsign is found. */
    public void setCallsignFoundListener(Consumer<String> listener) {
        this.callsignFoundListener = listener;
    }

    /** Adjust the sliding window size (default 128). */
    public void setWindowSize(int size) {
        if (size < 32) size = 32;
        this.windowSize = size;
        trimToWindow();
    }

    /** How many characters after the anchor to consider for pattern matching. */
    public void setSearchSpanAfterAnchor(int span) {
        if (span < 8) span = 8;
        this.searchSpanAfterAnchor = span;
    }

    /** Add a term to the false positives list (stored uppercase). */
    public void addFalsePositive(String term) {
        if (term != null && !term.isEmpty()) falsePositives.add(term.toUpperCase());
    }

    /** Replace the entire false positives set. Items are stored uppercase. */
    public void setFalsePositives(Set<String> terms) {
        falsePositives.clear();
        if (terms != null) {
            for (String t : terms) {
                if (t != null && !t.isEmpty()) falsePositives.add(t.toUpperCase());
            }
        }
    }

    public Set<String> getFalsePositives() {
        return Collections.unmodifiableSet(falsePositives);
    }

    public static Pattern getCallsignPattern() { return CALLSIGN_PATTERN; }

    // --- internals ---

    private void scanForCallsign() {
        if (buffer.length() == 0) return;
        String s = buffer.toString();

        // Find the latest anchor occurrence to reduce false hits from old text
        int bestIdx = -1;
        int bestAnchorLen = 0;
        for (String a : DEFAULT_ANCHORS) {
            int idx = s.lastIndexOf(a);
            if (idx > bestIdx) {
                bestIdx = idx;
                bestAnchorLen = a.length();
            }
        }
        if (bestIdx < 0) return; // no anchors in window

        int start = bestIdx + bestAnchorLen;
        int end = Math.min(s.length(), start + searchSpanAfterAnchor);
        if (start >= end) return;

        String tail = s.substring(start, end);
        Matcher m = CALLSIGN_PATTERN.matcher(tail);
        if (m.find()) {
            String call = m.group();
            if (isValidCall(call)) emit(call);
        }
    }

    private boolean isValidCall(String call) {
        if (call == null) return false;
        String u = call.toUpperCase();
        // Must contain at least one digit (already in regex) and not be in false positives
        if (falsePositives.contains(u)) return false;
        // Additional guardrails: length bounds
        if (u.length() < 3 || u.length() > 12) return false;
        return true;
    }

    private void emit(String call) {
        if (callsignFoundListener == null) return;
        if (Objects.equals(lastEmitted, call)) return; // suppress immediate dupes
        lastEmitted = call;
        callsignFoundListener.accept(call);
    }

    private void trimToWindow() {
        int overflow = buffer.length() - windowSize;
        if (overflow > 0) {
            buffer.delete(0, overflow);
        }
    }

    private static String sanitize(String in) {
        // Uppercase, strip non-basic characters, collapse whitespace
        String upper = in.toUpperCase();
        StringBuilder sb = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            // keep A-Z, 0-9, '/', and space-like separators normalized to single space
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '/') {
                sb.append(c);
            } else if (Character.isWhitespace(c)) {
                sb.append(' ');
            } // else drop
        }
        // collapse multiple spaces
        String collapsed = sb.toString().replaceAll(" +", " ");
        return collapsed;
    }

    private void loadDefaultFalsePositives() {
        // Common non-callsign words that sometimes appear near anchors
        String[] defaults = new String[]{
                // Provided examples
                "RADIO", "TUNER",
                // Additional frequent terms in decoded streams
                "ANTENNA", "ANT", "FILTER", "AUDIO", "MIC", "MODEM",
                "POWER", "WATTS", "RIG", "TEST", "HELLO", "WORLD",
                "CQ", "DE", "UR", "RST", "K", "BK", "PSE", "TNX",
                "CALL", "NAME", "QTH", "WX", "INFO", "USB", "LSB",
                // Obvious non-calls
                "OK", "YES", "NO"
        };
        falsePositives.addAll(Arrays.asList(defaults));
    }
}