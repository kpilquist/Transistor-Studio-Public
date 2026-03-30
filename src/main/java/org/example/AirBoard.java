package org.example;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scaffolding for the Air Board (Beacon Station BBS).
 * Uses NarrowGauge protocol for file transfers.
 */
public class AirBoard {
    private String stationCallsign = "N0CALL";
    private String beaconText = "Air Board BBS Ready";
    private int beaconIntervalMinutes = 10;
    private boolean beaconEnabled = false;
    
    // Thread-safe list for UI access
    private final List<BBSMessage> messages = new CopyOnWriteArrayList<>();

    public AirBoard() {
        // Add some dummy data for scaffolding
        messages.add(new BBSMessage("1", "SYSOP", "Welcome", "Welcome to the Air Board BBS!", System.currentTimeMillis()));
    }

    public static class BBSMessage {
        public String id;
        public String sender;
        public String subject;
        public String content;
        public long timestamp;

        public BBSMessage(String id, String sender, String subject, String content, long timestamp) {
            this.id = id;
            this.sender = sender;
            this.subject = subject;
            this.content = content;
            this.timestamp = timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("[%s] %s: %s", id, sender, subject);
        }
    }

    public void setStationCallsign(String callsign) {
        this.stationCallsign = callsign;
    }

    public String getStationCallsign() {
        return stationCallsign;
    }

    public void setBeaconText(String text) {
        this.beaconText = text;
    }

    public String getBeaconText() {
        return beaconText;
    }

    public void setBeaconIntervalMinutes(int minutes) {
        this.beaconIntervalMinutes = minutes;
    }

    public int getBeaconIntervalMinutes() {
        return beaconIntervalMinutes;
    }

    public void setBeaconEnabled(boolean enabled) {
        this.beaconEnabled = enabled;
    }

    public boolean isBeaconEnabled() {
        return beaconEnabled;
    }

    public List<BBSMessage> getMessages() {
        return messages;
    }

    public void addMessage(String sender, String subject, String content) {
        String id = String.valueOf(messages.size() + 1);
        messages.add(new BBSMessage(id, sender, subject, content, System.currentTimeMillis()));
    }
    
    // Placeholder for NarrowGauge integration
    public void syncFiles() {
        System.out.println("AirBoard: Initiating NarrowGauge sync...");
    }
}
