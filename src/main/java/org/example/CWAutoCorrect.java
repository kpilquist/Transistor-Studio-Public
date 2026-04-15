package org.example;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Lucene (optional at runtime; if present, we'll use it)
import org.apache.lucene.search.spell.PlainTextDictionary;
import org.apache.lucene.search.spell.SpellChecker;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.index.IndexWriterConfig;

/**
 * CWAutoCorrect provides a Morse-aware spell correction for single words.
 *
 * Prefers Apache Lucene's SpellChecker backend when available; otherwise falls
 * back to a simple in-memory dictionary with a Levenshtein-based suggestion.
 * It also implements Morse-aware single-element flip candidate generation.
 */
public class CWAutoCorrect {

    // In-memory dictionary of uppercase words (fallback)
    private final Set<String> dictionary = new HashSet<>();

    private final Map<Character, String> charToMorse = new HashMap<>();
    private final Map<String, Character> morseToChar = new HashMap<>();

    // Lucene backend (preferred when available)
    private SpellChecker spellChecker;
    private Directory spellIndexDirectory;

    /**
     * Create a CWAutoCorrect using the given plain-text dictionary file.
     * The dictionary should contain one word per line.
     */
    public CWAutoCorrect(File dictionaryFile) throws Exception {
        buildMorseMaps();
        if (dictionaryFile != null && dictionaryFile.exists()) {
            initLucene(dictionaryFile);
        } else {
            loadBuiltInDictionary();
            // Also init Lucene from built-in if possible
            File tmp = writeBuiltInDictionaryToTemp();
            if (tmp != null) initLucene(tmp);
        }
    }

    /**
     * Create a CWAutoCorrect with a small built-in dictionary so it works out-of-the-box.
     */
    public CWAutoCorrect() {
        try {
            buildMorseMaps();
            loadBuiltInDictionary();
            File tmp = writeBuiltInDictionaryToTemp();
            if (tmp != null) initLucene(tmp);
        } catch (Exception ignore) {
            // ignore
        }
    }

    private void loadDictionary(File file) throws Exception {
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // Keep words as uppercase for case-insensitive matching
                dictionary.add(line.toUpperCase(Locale.ROOT));
            }
        }
    }

    private void initLucene(File dictFile) throws Exception {
        try {
            spellIndexDirectory = new RAMDirectory();
            spellChecker = new SpellChecker(spellIndexDirectory);
            spellChecker.indexDictionary(new PlainTextDictionary(dictFile.toPath()), new IndexWriterConfig(), true);
        } catch (Throwable t) {
            // Lucene not on classpath or failed, fallback will be used
            spellChecker = null;
        }
        // Load into our fallback in-memory dictionary as well
        loadDictionary(dictFile);
    }

    private void loadBuiltInDictionary() {
        // Basic vocabulary to get the fallback running out-of-the-box
        String[] defaultWords = {
            "CQ", "DE", "RST", "QTH", "NAME", "RIG", "ANT", "WX", "PSE", "K", "KN", "SK", "73", "88",
            "THE", "AND", "THAT", "HAVE", "FOR", "NOT", "WITH", "YOU", "THIS", "BUT", "HELLO", "TEST"
        };
        for (String w : defaultWords) {
            dictionary.add(w);
        }
    }

    private File writeBuiltInDictionaryToTemp() {
        try {
            File tmp = File.createTempFile("cw_dict", ".txt");
            tmp.deleteOnExit();
            try (java.io.PrintWriter pw = new java.io.PrintWriter(tmp, StandardCharsets.UTF_8)) {
                for (String w : dictionary) {
                    pw.println(w);
                }
            }
            return tmp;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Corrects a single word. If it's valid, returns it. If not, attempts a Morse-aware correction,
     * else falls back to closest Levenshtein match from the dictionary.
     */
    public String correctWord(String word) {
        if (word == null || word.isEmpty()) return word;
        try {
            String original = word;
            String upper = word.toUpperCase(Locale.ROOT);

            if (exist(upper)) {
                return original; // valid as-is
            }

            // Try Morse-aware single-element flip candidates first
            List<String> candidates = generateMorseCandidates(upper);
            for (String c : candidates) {
                if (exist(c)) {
                    return matchCasing(c, original);
                }
            }

            // Fallback: nearest by edit distance (Levenshtein)
            String suggestion = suggestSimilar(upper, 1);
            if (suggestion != null) {
                return matchCasing(suggestion, original);
            }
        } catch (Exception ignore) {
            // Swallow and return original word
        }
        return word;
    }

    // Simple dictionary existence check
    private boolean exist(String upperWord) {
        if (spellChecker != null) {
            try {
                if (spellChecker.exist(upperWord)) return true;
            } catch (Exception ignore) {}
        }
        return dictionary.contains(upperWord);
    }

    // Simplified suggestion: return the best single candidate (k=1)
    private String suggestSimilar(String upperWord, int k) {
        if (spellChecker != null) {
            try {
                String[] suggestions = spellChecker.suggestSimilar(upperWord, k);
                if (suggestions != null && suggestions.length > 0) return suggestions[0];
            } catch (Exception ignore) {}
        }
        if (dictionary.isEmpty()) return null;
        int bestDist = Integer.MAX_VALUE;
        String best = null;
        for (String w : dictionary) {
            int d = levenshtein(upperWord, w, bestDist);
            if (d < bestDist) {
                bestDist = d;
                best = w;
                if (bestDist == 0) break;
            }
        }
        return (k > 0) ? best : null;
    }

    // Optimized Levenshtein with early cutoff
    private static int levenshtein(String a, String b, int cutoff) {
        int n = a.length();
        int m = b.length();
        if (a.equals(b)) return 0;
        if (n == 0) return m;
        if (m == 0) return n;

        // Swap to ensure n <= m for memory
        if (n > m) {
            String tmpS = a; a = b; b = tmpS;
            int tmp = n; n = m; m = tmp;
        }
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int i = 0; i <= n; i++) prev[i] = i;
        for (int j = 1; j <= m; j++) {
            char bj = b.charAt(j - 1);
            curr[0] = j;
            int rowMin = curr[0];
            for (int i = 1; i <= n; i++) {
                int cost = (a.charAt(i - 1) == bj) ? 0 : 1;
                int ins = curr[i - 1] + 1;
                int del = prev[i] + 1;
                int sub = prev[i - 1] + cost;
                int v = Math.min(Math.min(ins, del), sub);
                curr[i] = v;
                if (v < rowMin) rowMin = v;
            }
            if (rowMin > cutoff) return rowMin; // early exit if already worse
            int[] t = prev; prev = curr; curr = t;
        }
        return prev[n];
    }

    private static String matchCasing(String suggestionUpper, String original) {
        if (original.equals(original.toUpperCase(Locale.ROOT))) return suggestionUpper;
        if (original.equals(original.toLowerCase(Locale.ROOT))) return suggestionUpper.toLowerCase(Locale.ROOT);
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0))) {
            String lower = suggestionUpper.toLowerCase(Locale.ROOT);
            return Character.toUpperCase(lower.charAt(0)) + (lower.length() > 1 ? lower.substring(1) : "");
        }
        return suggestionUpper.toLowerCase(Locale.ROOT);
    }

    /**
     * Generate candidate words by flipping exactly one Morse element ('.' <-> '-')
     * in the Morse pattern of a single character within the word.
     */
    private List<String> generateMorseCandidates(String upperWord) {
        Set<String> out = new LinkedHashSet<>();
        char[] chars = upperWord.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            String pat = charToMorse.get(ch);
            if (pat == null || pat.isEmpty()) continue; // skip unknowns/spaces

            for (int j = 0; j < pat.length(); j++) {
                char e = pat.charAt(j);
                if (e != '.' && e != '-') continue;
                char flipped = (e == '.') ? '-' : '.';
                String var = pat.substring(0, j) + flipped + pat.substring(j + 1);
                Character mapped = morseToChar.get(var);
                if (mapped != null) {
                    char[] copy = Arrays.copyOf(chars, chars.length);
                    copy[i] = mapped;
                    out.add(new String(copy));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * Build simple Morse code maps. Duplicated from CWDecoder's internal table
     * to avoid changing its access.
     */
    private void buildMorseMaps() {
        // Letters A-Z
        put('A', ".-"); put('B', "-..."); put('C', "-.-."); put('D', "-.."); put('E', ".");
        put('F', "..-."); put('G', "--."); put('H', "...."); put('I', ".."); put('J', ".---");
        put('K', "-.-"); put('L', ".-.."); put('M', "--"); put('N', "-."); put('O', "---");
        put('P', ".--."); put('Q', "--.-"); put('R', ".-."); put('S', "..."); put('T', "-");
        put('U', "..-"); put('V', "...-"); put('W', ".--"); put('X', "-..-"); put('Y', "-.--");
        put('Z', "--..");
        // Digits 0-9
        put('1', ".----"); put('2', "..---"); put('3', "...--"); put('4', "....-"); put('5', ".....");
        put('6', "-...."); put('7', "--..."); put('8', "---.."); put('9', "----."); put('0', "-----");
        // Common punctuation
        put('.', ".-.-.-"); put(',', "--..--"); put('?', "..--.."); put('!', "-.-.--");
        put('-', "-....-"); put('/', "-..-."); put('@', ".--.-."); put('(', "-.--.");
        put(')', "-.--.-"); put(':', "---..."); put(';', "-.-.-."); put('"', ".-..-.");
        put('\'', ".----."); put('=', "-...-"); put('+', ".-.-.");
    }

    private void put(char ch, String pat) {
        charToMorse.put(ch, pat);
        morseToChar.put(pat, ch);
    }
}
