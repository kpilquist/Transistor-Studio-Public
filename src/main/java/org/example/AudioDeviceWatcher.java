package org.example;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Polls JavaSound mixers to detect when strictly-specified input/output devices
 * appear or disappear. Designed for USB composite radios whose audio endpoints
 * only enumerate after power on.
 */
public class AudioDeviceWatcher {
    public static class Identity {
        public final String name;
        public final String vendor;
        public final String description;
        public final String version;

        public Identity(String name, String vendor, String description, String version) {
            this.name = name == null ? "" : name;
            this.vendor = vendor == null ? "" : vendor;
            this.description = description == null ? "" : description;
            this.version = version == null ? "" : version;
        }

        public static Identity fromMixerInfo(Mixer.Info mi) {
            return new Identity(mi.getName(), mi.getVendor(), mi.getDescription(), mi.getVersion());
        }

        @Override
        public String toString() {
            return String.join("|", name, vendor, description, version);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Identity)) return false;
            Identity that = (Identity) o;
            return name.equals(that.name)
                    && vendor.equals(that.vendor)
                    && description.equals(that.description)
                    && version.equals(that.version);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, vendor, description, version);
        }
    }

    private final long pollMillis;
    private volatile Identity wantInput;
    private volatile Identity wantOutput;
    private volatile Mixer.Info currentInput;
    private volatile Mixer.Info currentOutput;
    private Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Consumer<Mixer.Info> onInputAppear;
    private Runnable onInputDisappear;
    private Consumer<Mixer.Info> onOutputAppear;
    private Runnable onOutputDisappear;

    // Debounce flags
    private volatile boolean lastInputPresent = false;
    private volatile boolean lastOutputPresent = false;

    public AudioDeviceWatcher(long pollMillis) {
        this.pollMillis = pollMillis <= 0 ? 750 : pollMillis;
    }

    public void setDesired(Identity input, Identity output) {
        this.wantInput = input;
        this.wantOutput = output;
    }

    public void setOnInputAppear(Consumer<Mixer.Info> cb) { this.onInputAppear = cb; }
    public void setOnInputDisappear(Runnable cb) { this.onInputDisappear = cb; }
    public void setOnOutputAppear(Consumer<Mixer.Info> cb) { this.onOutputAppear = cb; }
    public void setOnOutputDisappear(Runnable cb) { this.onOutputDisappear = cb; }

    public void start() {
        if (running.getAndSet(true)) return;
        thread = new Thread(this::runLoop, "AudioDeviceWatcher");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
    }

    private void runLoop() {
        while (running.get()) {
            try {
                probeOnce();
                Thread.sleep(pollMillis);
            } catch (InterruptedException ie) {
                break;
            } catch (Throwable ignored) {
                try { Thread.sleep(pollMillis); } catch (InterruptedException e) { break; }
            }
        }
    }

    private void probeOnce() {
        Mixer.Info[] infos = AudioSystem.getMixerInfo();

        // INPUT
        boolean inputPresent = false;
        Mixer.Info matchedIn = null;
        Identity wantIn = wantInput;
        if (wantIn != null) {
            for (Mixer.Info mi : infos) {
                Identity id = Identity.fromMixerInfo(mi);
                if (wantIn.equals(id)) { matchedIn = mi; inputPresent = true; break; }
            }
        }
        if (inputPresent != lastInputPresent) {
            lastInputPresent = inputPresent;
            if (inputPresent) {
                currentInput = matchedIn;
                if (onInputAppear != null) onInputAppear.accept(matchedIn);
            } else {
                currentInput = null;
                if (onInputDisappear != null) onInputDisappear.run();
            }
        }

        // OUTPUT
        boolean outputPresent = false;
        Mixer.Info matchedOut = null;
        Identity wantOut = wantOutput;
        if (wantOut != null) {
            for (Mixer.Info mi : infos) {
                Identity id = Identity.fromMixerInfo(mi);
                if (wantOut.equals(id)) { matchedOut = mi; outputPresent = true; break; }
            }
        }
        if (outputPresent != lastOutputPresent) {
            lastOutputPresent = outputPresent;
            if (outputPresent) {
                currentOutput = matchedOut;
                if (onOutputAppear != null) onOutputAppear.accept(matchedOut);
            } else {
                currentOutput = null;
                if (onOutputDisappear != null) onOutputDisappear.run();
            }
        }
    }
}
