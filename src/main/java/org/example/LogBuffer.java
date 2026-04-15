package org.example;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * A lightweight, thread-safe in-memory log buffer that keeps approximately the last minute of logs (FILO).
 * Provides utility methods to append logs and dump the current buffer snapshot to a file.
 */
public final class LogBuffer {
    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

    private static final LogBuffer INSTANCE = new LogBuffer();

    private static final Duration RETENTION = Duration.ofMinutes(1);
    private static final int SOFT_CAP = 10_000; // prevent unbounded growth if clock skew or spam

    private final Deque<Entry> deque = new ArrayDeque<>(); // newest at head for FILO semantics

    private LogBuffer() {}

    public static LogBuffer get() {
        return INSTANCE;
    }

    public static void trace(String msg) { get().append(Level.TRACE, msg, null); }
    public static void debug(String msg) { get().append(Level.DEBUG, msg, null); }
    public static void info(String msg)  { get().append(Level.INFO,  msg, null); }
    public static void warn(String msg)  { get().append(Level.WARN,  msg, null); }
    public static void error(String msg) { get().append(Level.ERROR, msg, null); }
    public static void error(String msg, Throwable t) { get().append(Level.ERROR, msg, t); }
    public static void fatal(String msg, Throwable t) { get().append(Level.FATAL, msg, t); }

    public void append(Level level, String message, Throwable t) {
        if (message == null) message = "";
        Instant now = Instant.now();
        synchronized (deque) {
            deque.addFirst(new Entry(now, level, message, t));
            pruneUnsafe(now);
            if (deque.size() > SOFT_CAP) {
                // Trim oldest if we somehow exceed soft cap
                while (deque.size() > SOFT_CAP) deque.removeLast();
            }
        }
    }

    /** Returns a snapshot (newest first) of the buffer within retention window. */
    public List<Entry> snapshot() {
        Instant now = Instant.now();
        List<Entry> copy = new ArrayList<>();
        synchronized (deque) {
            pruneUnsafe(now);
            copy.addAll(deque);
        }
        return copy;
    }

    /** Dumps the current buffer to the provided file. Creates parent dirs if needed. */
    public void dumpToFile(File file) throws IOException {
        List<Entry> snap = snapshot();
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            if (!parent.mkdirs() && !parent.exists()) {
                throw new IOException("Unable to create directories: " + parent);
            }
        }
        try (PrintWriter out = new PrintWriter(new FileOutputStream(file, false), true, StandardCharsets.UTF_8)) {
            out.println("==== Transistor Studio Log Dump ====");
            out.println("Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date()));
            out.println("Entries (newest first, last ~1 minute): " + snap.size());
            out.println();
            for (Entry e : snap) {
                out.println(e.formatLine());
                if (e.throwable != null) {
                    e.throwable.printStackTrace(out);
                }
            }
        }
    }

    /** Creates an auto-named log file in the default logs directory and dumps buffer there. */
    public File dumpToDefaultLocation(String reason) throws IOException {
        File dir = getDefaultLogsDir();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        String base = "transistor_log_" + ts;
        if (reason != null && !reason.isBlank()) {
            base += "_" + reason.replaceAll("[^a-zA-Z0-9\\-_]", "_").toLowerCase(Locale.ROOT);
        }
        File out = new File(dir, base + ".log");
        dumpToFile(out);
        return out;
    }

    public static File getDefaultLogsDir() {
        String userHome = System.getProperty("user.home");
        File dir = new File(userHome, "AppData/Local/TransistorStudio/logs");
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            dir = new File(userHome, "Library/Logs/TransistorStudio");
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("nux")) {
            dir = new File(userHome, ".local/share/TransistorStudio/logs");
        }
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void pruneUnsafe(Instant now) {
        Instant cutoff = now.minus(RETENTION);
        while (!deque.isEmpty()) {
            Entry last = deque.peekLast();
            if (last.timestamp.isBefore(cutoff)) {
                deque.removeLast();
            } else {
                break;
            }
        }
    }

    public static final class Entry {
        public final Instant timestamp;
        public final Level level;
        public final String message;
        public final Throwable throwable;

        Entry(Instant timestamp, Level level, String message, Throwable throwable) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
            this.throwable = throwable;
        }

        public String formatLine() {
            String ts = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(timestamp.toEpochMilli()));
            return String.format("%s [%s] %s", ts, level, message);
        }
    }
}
