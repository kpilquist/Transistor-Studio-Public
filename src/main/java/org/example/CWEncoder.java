package org.example;

import java.util.*;

/**
 * Simple CW (Morse) audio encoder.
 * - Configurable tone frequency and WPM
 * - Generates monophonic PCM float samples (-1..1)
 */
public class CWEncoder implements SignalEncoder {
    private int toneHz = 700;        // default sidetone
    private int wpm = 20;            // default WPM
    private double riseFallMs = 5.0; // keying envelope to reduce clicks

    private static final Map<Character, String> MORSE = new HashMap<>();
    static {
        // ITU Morse for A-Z, 0-9, and some punctuation
        MORSE.put('A', ".-"); MORSE.put('B', "-..."); MORSE.put('C', "-.-."); MORSE.put('D', "-..");
        MORSE.put('E', "."); MORSE.put('F', "..-."); MORSE.put('G', "--."); MORSE.put('H', "....");
        MORSE.put('I', ".."); MORSE.put('J', ".---"); MORSE.put('K', "-.-"); MORSE.put('L', ".-..");
        MORSE.put('M', "--"); MORSE.put('N', "-."); MORSE.put('O', "---"); MORSE.put('P', ".--.");
        MORSE.put('Q', "--.-"); MORSE.put('R', ".-."); MORSE.put('S', "..."); MORSE.put('T', "-");
        MORSE.put('U', "..-"); MORSE.put('V', "...-"); MORSE.put('W', ".--"); MORSE.put('X', "-..-");
        MORSE.put('Y', "-.--"); MORSE.put('Z', "--..");
        MORSE.put('0', "-----"); MORSE.put('1', ".----"); MORSE.put('2', "..---"); MORSE.put('3', "...--");
        MORSE.put('4', "....-"); MORSE.put('5', "....."); MORSE.put('6', "-...."); MORSE.put('7', "--...");
        MORSE.put('8', "---.."); MORSE.put('9', "----.");
        MORSE.put('.', ".-.-.-"); MORSE.put(',', "--..--"); MORSE.put('?', "..--..");
        MORSE.put('/', "-..-."); MORSE.put('-', "-....-"); MORSE.put('(', "-.--."); MORSE.put(')', "-.--.-");
        MORSE.put('@', ".--.-."); MORSE.put(':', "---..."); MORSE.put(';', "-.-.-."); MORSE.put('=', "-...-");
        MORSE.put('+', ".-.-."); MORSE.put('"', ".-..-."); MORSE.put('!', "-.-.--"); MORSE.put(' ', " "); // word gap placeholder
    }

    public void setToneHz(int toneHz) { this.toneHz = Math.max(200, Math.min(toneHz, 3000)); }
    public void setWpm(int wpm) { this.wpm = Math.max(5, Math.min(wpm, 50)); }
    public void setRiseFallMs(double ms) { this.riseFallMs = Math.max(1.0, Math.min(ms, 20.0)); }
    public int getToneHz() { return toneHz; }

    @Override
    public float[] generateSamples(String text, int sampleRate) {
        if (text == null) text = "";
        String up = text.toUpperCase(Locale.ROOT);
        List<Float> out = new ArrayList<>();

        // Timing: standard PARIS definition: dit (dot) length = 1200 / WPM milliseconds
        double ditMs = 1200.0 / wpm;
        double dahMs = 3 * ditMs;
        double intraGapMs = ditMs;     // between elements inside a character
        double charGapMs = 3 * ditMs;  // between characters
        double wordGapMs = 7 * ditMs;  // between words

        // Pre-key a short lead-in (optional)
        appendSilence(out, msToSamples(ditMs * 2, sampleRate));

        for (int ci = 0; ci < up.length(); ci++) {
            char ch = up.charAt(ci);
            if (ch == ' ') {
                // word gap (ensure we at least have the 7-unit gap)
                appendSilence(out, msToSamples(wordGapMs, sampleRate));
                continue;
            }
            String code = MORSE.get(ch);
            if (code == null) {
                // skip unknowns
                continue;
            }
            for (int i = 0; i < code.length(); i++) {
                char sym = code.charAt(i);
                double durMs = (sym == '.') ? ditMs : dahMs;
                tone(out, toneHz, durMs, sampleRate);
                // intra-element gap after each element except last in char
                if (i < code.length() - 1) {
                    appendSilence(out, msToSamples(intraGapMs, sampleRate));
                }
            }
            // char gap after each char except when next is space or end
            if (ci < up.length() - 1 && up.charAt(ci + 1) != ' ') {
                appendSilence(out, msToSamples(charGapMs, sampleRate));
            }
        }

        // Post tail
        appendSilence(out, msToSamples(ditMs * 2, sampleRate));

        float[] arr = new float[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private void appendSilence(List<Float> out, int count) {
        for (int i = 0; i < count; i++) out.add(0f);
    }

    private int msToSamples(double ms, int sampleRate) {
        return (int) Math.round(ms * sampleRate / 1000.0);
    }

    private void tone(List<Float> out, int freq, double durMs, int sampleRate) {
        int n = msToSamples(durMs, sampleRate);
        double omega = 2 * Math.PI * freq / sampleRate;
        // Raised cosine envelope at both edges
        int edge = Math.min(msToSamples(riseFallMs, sampleRate), Math.max(1, n / 10));
        for (int i = 0; i < n; i++) {
            double env;
            if (i < edge) {
                double t = (i + 1) / (double) edge;
                env = 0.5 - 0.5 * Math.cos(Math.PI * t);
            } else if (i > n - edge) {
                double t = (n - i) / (double) edge;
                env = 0.5 - 0.5 * Math.cos(Math.PI * t);
            } else {
                env = 1.0;
            }
            float s = (float) (env * Math.sin(omega * i));
            out.add(s);
        }
    }
}
