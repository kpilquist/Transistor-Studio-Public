package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * NodeTracker manages awareness of active nodes in the mesh network.
 * It records last-seen timestamps and sweeps periodically to evict stale nodes.
 */
public class NodeTracker {
    private static final long OFFLINE_THRESHOLD_MS = 300_000L; // 5 minutes
    private static final long SWEEP_PERIOD_SECONDS = 60L;      // 60 seconds

    private final ConcurrentMap<String, Long> activeNodes = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public NodeTracker() {
        // Scheduled background sweeper
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "NodeTracker-Sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sweep, SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Updates or adds the callsign with the current system time in milliseconds.
     */
    public void logNodeActivity(String callsign) {
        String key = normalizeCallsign(callsign);
        activeNodes.put(key, System.currentTimeMillis());
    }

    /**
     * Returns a snapshot list of all currently active callsigns.
     */
    public List<String> getActiveNodes() {
        return new ArrayList<>(activeNodes.keySet());
    }

    /**
     * Background task: remove nodes idle beyond OFFLINE_THRESHOLD_MS.
     */
    private void sweep() {
        long now = System.currentTimeMillis();
        for (String callsign : new ArrayList<>(activeNodes.keySet())) {
            Long lastSeen = activeNodes.get(callsign);
            if (lastSeen == null) continue;
            if (now - lastSeen > OFFLINE_THRESHOLD_MS) {
                activeNodes.remove(callsign, lastSeen);
                System.out.println("[NodeTracker] Node offline: " + callsign);
            }
        }
    }

    /**
     * Optional: call when shutting down the server to stop the scheduler.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }

    private static String normalizeCallsign(String s) {
        Objects.requireNonNull(s, "callsign");
        // Trim outer spaces and collapse to uppercase as callsigns are typically case-insensitive
        return s.trim().toUpperCase();
    }

    // Expose for testing/inspection as per spec name
    public ConcurrentHashMap<String, Long> getActiveNodesMap() {
        return new ConcurrentHashMap<>(activeNodes);
    }
}