package org.example;

import javax.sound.sampled.*;

public class AudioPlayback {
    private SourceDataLine sourceDataLine;
    private int sampleRate = 48000;

    // Output level (software gain) and mute
    private volatile double outputGain = 1.0; // 0.0..1.0
    private volatile boolean muted = false;

    // Turnaround delay after TX to allow radio relays/AGC to settle (ms)
    private volatile int turnaroundDelayMs = 50; // default 50 ms (20..100 ms typical)

    public void setTurnaroundDelayMs(int ms) {
        if (ms < 0) ms = 0;
        if (ms > 500) ms = 500; // cap to avoid excessively long mute
        this.turnaroundDelayMs = ms;
    }

    public int getTurnaroundDelayMs() { return turnaroundDelayMs; }

    // Tone generation state
    private Thread toneThread;
    private volatile boolean toneRunning = false;
    private volatile int toneHz = 0;

    // Looped playback state (for idle PSK/RTTY patterns)
    private Thread loopThread;
    private volatile boolean loopRunning = false;
    private byte[] loopBuffer; // 16-bit PCM little-endian

    // Streaming generation state (for continuous PSK31 idle, etc.)
    private Thread streamThread;
    private volatile boolean streamRunning = false;

    public void start(String deviceName) throws LineUnavailableException {
        // Legacy: resolve by name and delegate to strict overload
        Mixer.Info selectedMixerInfo = null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(deviceName)) {
                selectedMixerInfo = info;
                break;
            }
        }
        if (selectedMixerInfo == null) {
            throw new LineUnavailableException("Device not found: " + deviceName);
        }
        start(selectedMixerInfo);
    }

    /**
     * Strict start using an exact Mixer.Info instance (name/vendor/desc/version match).
     */
    public void start(Mixer.Info selectedMixerInfo) throws LineUnavailableException {
        if (selectedMixerInfo == null) throw new LineUnavailableException("Selected mixer is null");
        Mixer mixer = AudioSystem.getMixer(selectedMixerInfo);
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!mixer.isLineSupported(info)) {
            throw new LineUnavailableException("Line not supported for mixer: " + selectedMixerInfo.getName());
        }
        sourceDataLine = (SourceDataLine) mixer.getLine(info);
        sourceDataLine.open(format);
        sourceDataLine.start();
    }

    public void setOutputGain(double gain) {
        if (Double.isNaN(gain) || Double.isInfinite(gain)) return;
        if (gain < 0.0) gain = 0.0;
        if (gain > 1.0) gain = 1.0;
        this.outputGain = gain;
    }

    public double getOutputGain() { return outputGain; }

    public void setMuted(boolean m) { this.muted = m; }
    public boolean isMuted() { return muted; }

    private void writePcmWithGain(byte[] buffer, int len) {
        if (sourceDataLine == null) return;
        double g = muted ? 0.0 : outputGain;
        if (g >= 0.999 && g <= 1.001) {
            try { sourceDataLine.write(buffer, 0, len); } catch (Exception ignore) {}
            return;
        }
        // Software scale 16-bit little-endian mono in-place copy
        // To avoid mutating the original, make a temp buffer
        byte[] tmp = new byte[len];
        System.arraycopy(buffer, 0, tmp, 0, len);
        int samples = len / 2;
        for (int i = 0; i < samples; i++) {
            int lo = tmp[i * 2] & 0xFF;
            int hi = tmp[i * 2 + 1]; // signed
            int s = (hi << 8) | lo;
            int out = (int) Math.round(s * g);
            if (out > 32767) out = 32767;
            if (out < -32768) out = -32768;
            tmp[i * 2] = (byte) (out & 0xFF);
            tmp[i * 2 + 1] = (byte) ((out >> 8) & 0xFF);
        }
        try { sourceDataLine.write(tmp, 0, len); } catch (Exception ignore) {}
    }

    public void play(float[] samples) {
        // Always mirror into loopback bus for local test mode
        try { LoopbackBus.getInstance().write(samples); } catch (Throwable ignore) {}

        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        // Stop any running idle (tone, loop, or stream) so the buffered send is not mixed
        stopTone();
        stopLoop();
        stopStream();

        byte[] buffer = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short val = (short) (samples[i] * 32767);
            buffer[i * 2] = (byte) (val & 0xFF);
            buffer[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        writePcmWithGain(buffer, buffer.length);
        // Turnaround delay to allow relays/AGC to settle
        if (turnaroundDelayMs > 0) {
            writeSilenceMs(turnaroundDelayMs);
        }
    }

    /**
     * Plays a short, graceful close tail for PSK31: a steady carrier that smoothly
     * ramps down to silence. This helps receivers see a proper close and prevents
     * leaving residual keyed audio.
     */
    public void playCloseTailPsk31(int carrierHz, int durationMs) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        // Preempt generators so we own the line
        stopTone();
        stopLoop();
        stopStream();
        int dur = durationMs <= 0 ? 80 : durationMs; // default ~80 ms
        int totalSamples = Math.max(1, (int) Math.round(dur * sampleRate / 1000.0));
        int freq = Math.max(1, Math.min(carrierHz, sampleRate / 2 - 1));
        byte[] buf = new byte[totalSamples * 2];
        // Raised-cosine from 1 -> 0 to avoid clicks at the end while matching prior level at start
        for (int i = 0; i < totalSamples; i++) {
            double w = 0.5 * (1.0 + Math.cos(Math.PI * i / totalSamples)); // 1..0
            double sample = w * Math.cos(2 * Math.PI * freq * (i / (double) sampleRate));
            short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32767.0)));
            buf[i * 2] = (byte) (s & 0xFF);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        try {
            writePcmWithGain(buf, buf.length);
            // Follow with a few ms of zeros to guarantee complete silence and flush buffers
            writeSilenceMs(10);
            sourceDataLine.flush();
        } catch (Exception ignore) {}
    }

    /**
     * Plays a short, graceful close tail for RTTY: MARK tone that smoothly
     * ramps down to silence.
     */
    public void playCloseTailRtty(int markHz, int durationMs) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        // Preempt generators
        stopTone();
        stopLoop();
        stopStream();
        int dur = durationMs <= 0 ? 100 : durationMs; // default ~100 ms
        int totalSamples = Math.max(1, (int) Math.round(dur * sampleRate / 1000.0));
        int freq = Math.max(1, Math.min(markHz, sampleRate / 2 - 1));
        byte[] buf = new byte[totalSamples * 2];
        for (int i = 0; i < totalSamples; i++) {
            double w = 0.5 * (1.0 + Math.cos(Math.PI * i / totalSamples)); // 1..0
            double sample = w * Math.sin(2 * Math.PI * freq * (i / (double) sampleRate));
            short s = (short) (Math.max(-1.0, Math.min(1.0, sample)) * 32767);
            buf[i * 2] = (byte) (s & 0xFF);
            buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        try {
            sourceDataLine.write(buf, 0, buf.length);
            writeSilenceMs(10);
            sourceDataLine.flush();
        } catch (Exception ignore) {}
    }

    private void writeSilenceMs(int ms) {
        int n = Math.max(1, (int) Math.round(ms * sampleRate / 1000.0));
        byte[] zeros = new byte[n * 2];
        try { sourceDataLine.write(zeros, 0, zeros.length); } catch (Exception ignore) {}
    }

    public synchronized void startTone(int hz) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        // If same tone already running, ignore
        if (toneRunning && hz == toneHz) return;
        // Stop previous generators if any
        stopLoop();
        stopTone();
        toneHz = Math.max(1, Math.min(hz, sampleRate / 2 - 1));
        toneRunning = true;
        toneThread = new Thread(() -> {
            int frames = 512;
            byte[] buf = new byte[frames * 2]; // 16-bit mono
            double phase = 0.0;
            double omega = 2 * Math.PI * toneHz / sampleRate;
            while (toneRunning) {
                for (int i = 0; i < frames; i++) {
                    short s = (short) (Math.sin(phase) * 32767);
                    buf[i * 2] = (byte) (s & 0xFF);
                    buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                    phase += omega;
                    if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                }
                try {
                    writePcmWithGain(buf, buf.length);
                } catch (Exception ex) {
                    break;
                }
            }
        }, "ToneThread");
        toneThread.setDaemon(true);
        toneThread.start();
    }

    public synchronized void stopTone() {
        toneRunning = false;
        if (toneThread != null) {
            try { toneThread.join(50); } catch (InterruptedException ignored) {}
            toneThread = null;
        }
    }

    public synchronized void startLoop(float[] samples) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        if (samples == null || samples.length == 0) return;
        // Stop previous generators
        stopTone();
        stopLoop();

        // Apply a short crossfade at the loop seam to prevent pops/clicks when repeating
        float[] processed = applySeamlessLoopFade(samples, sampleRate, 8); // 8 ms fade by default

        // Convert once to 16-bit PCM little-endian
        loopBuffer = new byte[processed.length * 2];
        for (int i = 0; i < processed.length; i++) {
            short val = (short) (Math.max(-1f, Math.min(1f, processed[i])) * 32767);
            loopBuffer[i * 2] = (byte) (val & 0xFF);
            loopBuffer[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        loopRunning = true;
        loopThread = new Thread(() -> {
            try {
                while (loopRunning) {
                    writePcmWithGain(loopBuffer, loopBuffer.length);
                }
            } catch (Exception ignore) {
            }
        }, "LoopThread");
        loopThread.setDaemon(true);
        loopThread.start();
    }

    // Seamless loop helper: applies equal-power fade at the start and end and crossfades the tail towards the head
    private float[] applySeamlessLoopFade(float[] in, int sr, int fadeMs) {
        if (in == null) return new float[0];
        int n = in.length;
        if (n < 10) return in;
        int fadeSamples = Math.max(1, Math.min(n / 8, (int) Math.round(fadeMs * sr / 1000.0)));
        if (fadeSamples * 2 >= n) {
            // Too short for meaningful fade, just return original
            return in;
        }
        float[] out = new float[n];
        System.arraycopy(in, 0, out, 0, n);

        // Equal-power ramp for start (fade-in) and end (fade-out)
        for (int i = 0; i < fadeSamples; i++) {
            double t = (i + 1) / (double) fadeSamples; // 0..1
            // equal-power curve
            float fadeIn = (float) Math.sin(0.5 * Math.PI * t);
            float fadeOut = (float) Math.cos(0.5 * Math.PI * t);
            // Start fade-in: multiply
            out[i] *= fadeIn;
            // End fade-out: multiply
            int idxEnd = n - fadeSamples + i;
            out[idxEnd] *= fadeOut;
        }

        // Crossfade tail towards the head so the last sample equals the first sample when wrapping
        for (int i = 0; i < fadeSamples; i++) {
            double t = (i + 1) / (double) fadeSamples; // 0..1
            float wHead = (float) t;         // increasing weight to head
            float wTail = (float) (1.0 - t); // decreasing weight to tail
            int idxEnd = n - fadeSamples + i;
            float mixed = out[idxEnd] * wTail + out[i] * wHead;
            out[idxEnd] = mixed;
        }
        return out;
    }

    public synchronized void stopLoop() {
        loopRunning = false;
        if (loopThread != null) {
            try { loopThread.join(50); } catch (InterruptedException ignored) {}
            loopThread = null;
        }
    }

    // Start a continuous PSK31 idle generator that runs without looping buffers
    public synchronized void startPsk31Idle(int carrierHz) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        // Stop any existing generators
        stopTone();
        stopLoop();
        stopStream();
        final int freq = Math.max(1, Math.min(carrierHz, sampleRate / 2 - 1));
        streamRunning = true;
        streamThread = new Thread(() -> runPsk31IdleStream(freq), "PSK31IdleStream");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void runPsk31IdleStream(int carrierHz) {
        final double baud = 31.25;
        final double samplesPerBit = sampleRate / baud;
        final int frames = 512; // write chunk size
        byte[] buf = new byte[frames * 2]; // 16-bit mono
        double time = 0.0;
        double currentAmplitude = 1.0; // +1 / -1 toggles each bit
        double samplesIntoBit = 0.0;
        // Gentle fade-in over ~5 ms to avoid start click
        int fadeSamples = Math.max(1, (int) Math.round(0.005 * sampleRate));
        int totalGenerated = 0;
        while (streamRunning) {
            for (int i = 0; i < frames; i++) {
                double tRel = samplesIntoBit / samplesPerBit; // 0..1 within bit
                // For idle (all zeros), each bit is a reversal shaped by cosine across the bit
                double envelope = currentAmplitude * Math.cos(Math.PI * tRel);
                double sample = envelope * Math.cos(2 * Math.PI * carrierHz * time);
                // Apply fade-in at the very beginning
                if (totalGenerated < fadeSamples) {
                    double w = (totalGenerated + 1) / (double) fadeSamples; // 0..1
                    sample *= 0.5 - 0.5 * Math.cos(Math.PI * w); // raised cosine in
                }
                short s = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * 32767.0)));
                buf[i * 2] = (byte) (s & 0xFF);
                buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);

                time += 1.0 / sampleRate;
                samplesIntoBit += 1.0;
                totalGenerated++;
                if (samplesIntoBit >= samplesPerBit) {
                    samplesIntoBit -= samplesPerBit;
                    currentAmplitude = -currentAmplitude; // reverse each bit
                }
            }
            try {
                writePcmWithGain(buf, buf.length);
            } catch (Exception ex) {
                break;
            }
        }
    }

    public synchronized void stopStream() {
        streamRunning = false;
        if (streamThread != null) {
            try { streamThread.join(50); } catch (InterruptedException ignored) {}
            streamThread = null;
        }
    }

    // Start a continuous RTTY idle generator (AFSK) that streams "RY " pattern endlessly with proper timing
    public synchronized void startRttyIdle(int markHz, int spaceHz, double baud) {
        if (sourceDataLine == null || !sourceDataLine.isOpen()) return;
        if (baud <= 0) baud = 45.45;
        // Stop any existing generators
        stopTone();
        stopLoop();
        stopStream();
        final int mark = Math.max(1, Math.min(markHz, sampleRate / 2 - 1));
        final int space = Math.max(1, Math.min(spaceHz, sampleRate / 2 - 1));
        final double useBaud = baud;
        streamRunning = true;
        streamThread = new Thread(() -> runRttyIdleStream(mark, space, useBaud), "RTTYIdleStream");
        streamThread.setDaemon(true);
        streamThread.start();
    }

    private void runRttyIdleStream(int markHz, int spaceHz, double baud) {
        final double samplesPerBit = sampleRate / baud;
        final int frames = 512; // chunk size
        byte[] buf = new byte[frames * 2];
        // Frequencies
        final double omegaMark = 2 * Math.PI * markHz / sampleRate;
        final double omegaSpace = 2 * Math.PI * spaceHz / sampleRate;
        double phase = 0.0;
        // Pattern of characters: R(10), Y(21), space(4) in ITA2 letters
        final int[] codes = new int[] {10, 21, 4};
        int charIndex = 0;
        int bitIndexInChar = -1; // -1=start bit, 0..4 data bits, 5.. for stop bits
        double samplesLeftInBit = 0.0;
        boolean currentBitMark = true; // mark=1, space=0
        double omega = omegaMark;
        // gentle fade-in at stream start (~5 ms)
        int fadeSamples = Math.max(1, (int) Math.round(0.005 * sampleRate));
        int totalGenerated = 0;
        // initialize first bit
        bitIndexInChar = -1; // start bit first
        samplesLeftInBit = samplesPerBit;
        currentBitMark = false; // start bit is SPACE (0)
        omega = omegaSpace;
        int currentCode = codes[charIndex];
        double stopBitsRemaining = 0.0; // for 1.5 stop bits handling

        while (streamRunning) {
            for (int i = 0; i < frames; i++) {
                // Generate one sample at current frequency with continuous phase
                double sample = Math.sin(phase);
                // Apply fade-in only at the very beginning
                if (totalGenerated < fadeSamples) {
                    double w = (totalGenerated + 1) / (double) fadeSamples;
                    sample *= 0.5 - 0.5 * Math.cos(Math.PI * w);
                }
                short s = (short) (Math.max(-1.0, Math.min(1.0, sample)) * 32767);
                buf[i * 2] = (byte) (s & 0xFF);
                buf[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);

                // advance time
                phase += omega;
                if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                samplesLeftInBit -= 1.0;
                totalGenerated++;

                if (samplesLeftInBit <= 0) {
                    // Advance to next bit in the frame sequence
                    if (bitIndexInChar == -1) {
                        // finished start bit, go to first data bit (LSB)
                        bitIndexInChar = 0;
                        boolean bit = ((currentCode >> bitIndexInChar) & 1) == 1; // 1=mark, 0=space
                        currentBitMark = bit;
                        omega = currentBitMark ? omegaMark : omegaSpace;
                        samplesLeftInBit += samplesPerBit;
                    } else if (bitIndexInChar >= 0 && bitIndexInChar <= 3) {
                        // next data bit (within 0..3), move to next
                        bitIndexInChar++;
                        boolean bit = ((currentCode >> bitIndexInChar) & 1) == 1;
                        currentBitMark = bit;
                        omega = currentBitMark ? omegaMark : omegaSpace;
                        samplesLeftInBit += samplesPerBit;
                    } else if (bitIndexInChar == 4) {
                        // completed last data bit, start stop bits (1.5 bits of MARK)
                        bitIndexInChar++;
                        currentBitMark = true;
                        omega = omegaMark;
                        stopBitsRemaining = 1.5; // in bit units
                        samplesLeftInBit += samplesPerBit; // handle one bit at a time
                    } else if (bitIndexInChar >= 5) {
                        // we are sending stop bits; decrement remaining
                        stopBitsRemaining -= 1.0;
                        if (stopBitsRemaining > 0.5) {
                            // still >= 0.5+ left, send another full bit of MARK
                            currentBitMark = true;
                            omega = omegaMark;
                            samplesLeftInBit += samplesPerBit;
                        } else {
                            // last half bit handled by setting samplesLeft accordingly
                            if (stopBitsRemaining > 0) {
                                currentBitMark = true;
                                omega = omegaMark;
                                samplesLeftInBit += samplesPerBit * stopBitsRemaining;
                                stopBitsRemaining = 0.0; // will finish next
                            } else {
                                // Move to next character in pattern and emit its start bit
                                charIndex = (charIndex + 1) % codes.length;
                                currentCode = codes[charIndex];
                                bitIndexInChar = -1;
                                currentBitMark = false; // start bit is SPACE
                                omega = omegaSpace;
                                samplesLeftInBit += samplesPerBit;
                            }
                        }
                    }
                }
            }
            try {
                sourceDataLine.write(buf, 0, buf.length);
            } catch (Exception ex) {
                break;
            }
        }
    }

    public synchronized void stopAllTx() {
        stopTone();
        stopLoop();
        stopStream();
        try {
            if (sourceDataLine != null) {
                sourceDataLine.flush();
            }
        } catch (Exception ignore) {}
    }

    public void stop() {
        stopTone();
        stopLoop();
        stopStream();
        if (sourceDataLine != null) {
            sourceDataLine.drain();
            sourceDataLine.stop();
            sourceDataLine.close();
            sourceDataLine = null;
        }
    }

    public int getSampleRate() {
        return sampleRate;
    }
}

