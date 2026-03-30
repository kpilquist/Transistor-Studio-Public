package org.example;

import org.jtransforms.fft.DoubleFFT_1D;

import javax.sound.sampled.*;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Handles capturing audio from a specific device, performing FFT,
 * and notifying listeners with frequency domain data.
 */
public class AudioCapture {
    private TargetDataLine targetDataLine;
    private Thread captureThread;
    private Thread processingThread;
    private volatile boolean running = false;
    private int sampleRate = 48000;
    private int channels = 1;
    private volatile int fftSize = 16384; // Higher frequency resolution
    private volatile int processingBandwidth = 0; // 0 means full bandwidth
    private volatile int targetFps = 30;
    private volatile int overlapFactor = 2; // 1 = no overlap, 2 = 50%, 4 = 75%, etc.
    //private volatile int fftWorkerThreads = 1;
    private volatile int fftWorkerThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    private java.util.concurrent.ExecutorService fftExecutor;
    private java.util.concurrent.ConcurrentLinkedQueue<double[]> fftBufferPool = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean fpsCapActive = false;

    // Zoom-linked processing flags (stage A: auto-resolution only)
    private volatile boolean linkProcessingToZoom = true;
    private volatile boolean autoResolutionOnZoom = true;
    private volatile double zoomStartHz = 0;
    private volatile double zoomEndHz = 0;

    private Consumer<double[]> spectrumListener;
    private final List<Consumer<float[]>> audioListeners = new CopyOnWriteArrayList<>();
    private final BlockingQueue<float[]> audioQueue = new LinkedBlockingQueue<>();

    public void setFftSize(int size) {
        this.fftSize = size;
        // The processAudio loop will pick up the change if we handle it there,
        // or we can just restart the capture.
        // For simplicity and safety, let's just let the loop handle it by checking size.
    }

    public void setOverlapFactor(int overlap) {
        if (overlap < 1) overlap = 1;
        this.overlapFactor = overlap;
    }

    // Zoom processing controls
    public void setLinkProcessingToZoom(boolean enabled) {
        this.linkProcessingToZoom = enabled;
        applyZoomPolicy();
    }

    public void setAutoResolutionOnZoom(boolean enabled) {
        this.autoResolutionOnZoom = enabled;
        applyZoomPolicy();
    }

    public void setZoomWindow(double startHz, double endHz) {
        this.zoomStartHz = startHz;
        this.zoomEndHz = endHz;
        applyZoomPolicy();
    }

    public void setFftWorkerThreads(int threads) {
        if (threads < 1) threads = 1;
        this.fftWorkerThreads = threads;
        // Rebuild executor if running
        if (fftExecutor != null && !fftExecutor.isShutdown()) {
            fftExecutor.shutdownNow();
        }
        fftExecutor = java.util.concurrent.Executors.newFixedThreadPool(fftWorkerThreads, r -> {
            Thread t = new Thread(r, "FFT-Worker");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY);
            return t;
        });
    }

    public void setProcessingBandwidth(int bandwidth) {
        this.processingBandwidth = bandwidth;
    }

    public void setTargetFps(int fps) {
        this.targetFps = fps;
    }

    public void addAudioListener(Consumer<float[]> listener) {
        audioListeners.add(listener);
    }

    public void removeAudioListener(Consumer<float[]> listener) {
        audioListeners.remove(listener);
    }

    /**
     * Starts capturing audio from the specified device.
     *
     * @param deviceName The name of the mixer/device to use.
     * @throws LineUnavailableException If the device cannot be opened.
     */
    public void start(String deviceName, Consumer<double[]> listener) throws LineUnavailableException {
        // Legacy: resolve by name and delegate to strict overload
        Mixer.Info selectedMixerInfo = null;
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (info.getName().equals(deviceName)) {
                selectedMixerInfo = info;
                break;
            }
        }
        if (selectedMixerInfo == null) throw new LineUnavailableException("Device not found: " + deviceName);
        start(selectedMixerInfo, listener);
    }

    /**
     * Strict start using an exact Mixer.Info instance (name/vendor/desc/version match).
     */
    public void start(Mixer.Info selectedMixerInfo, Consumer<double[]> listener) throws LineUnavailableException {
        this.spectrumListener = listener;

        // Prepare FFT executor
        if (fftExecutor == null || fftExecutor.isShutdown()) {
            setFftWorkerThreads(fftWorkerThreads);
        }

        // Use the provided mixer info strictly (name/vendor/desc/version)
        if (selectedMixerInfo == null) {
            throw new LineUnavailableException("Selected mixer is null");
        }

        Mixer mixer = AudioSystem.getMixer(selectedMixerInfo);

        // Try common sample rates.
        // Prefer 48kHz and 44.1kHz as they are standard for most audio devices.
        int[] rates = {48000, 44100, 96000, 192000};
        int[] channelCounts = {2, 1}; // Try stereo first to avoid potential mono voice processing
        boolean lineOpened = false;

        for (int rate : rates) {
            for (int channelCount : channelCounts) {
                try {
                    // Use Little Endian (false) as it is standard for PC audio
                    AudioFormat format = new AudioFormat(rate, 16, channelCount, true, false);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

                    if (mixer.isLineSupported(info)) {
                        targetDataLine = (TargetDataLine) mixer.getLine(info);
                        targetDataLine.open(format);
                        this.sampleRate = rate;
                        this.channels = channelCount;
                        lineOpened = true;
                        System.out.println("AudioCapture: Opened line with sample rate: " + rate + ", channels: " + channelCount);
                        break;
                    }
                } catch (Exception e) {
                    // Try next configuration
                }
            }
            if (lineOpened) break;
        }

        if (!lineOpened) {
             throw new LineUnavailableException("Could not open line with any supported configuration.");
        }

        targetDataLine.start();

        running = true;
        audioQueue.clear();

        captureThread = new Thread(this::captureLoop);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();

        processingThread = new Thread(this::processingLoop);
        processingThread.setPriority(Thread.NORM_PRIORITY);
        processingThread.start();

        System.out.println("Audio capture started on: " + selectedMixerInfo.getName());
    }

    public void stop() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (processingThread != null) {
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (fftExecutor != null) {
            fftExecutor.shutdownNow();
        }
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
        }
        System.out.println("Audio capture stopped.");
    }

    public int getEffectiveSampleRate() {
        if (sampleRate == 0) return 0;
        int factor = 1;
        if (processingBandwidth > 0) {
            factor = sampleRate / (2 * processingBandwidth);
            if (factor < 1) factor = 1;
        }
        return sampleRate / factor;
    }

    // Apply zoom-linked processing policy: adjust processing bandwidth and resolution
    private void applyZoomPolicy() {
        if (!linkProcessingToZoom) return;
        double start = zoomStartHz;
        double end = zoomEndHz;
        if (end <= start) return;
        double width = end - start;
        // Clamp width to sensible bounds
        double minBw = 500.0; // 500 Hz minimum to avoid extreme factors
        int maxBw = sampleRate / 2;
        int desiredBw = (int) Math.max(minBw, Math.min(width, maxBw));
        // Only narrow processing bandwidth if the zoom window starts near DC; otherwise keep wideband until we implement heterodyne.
        if (start < 100.0) {
            this.processingBandwidth = desiredBw;
        }

        if (autoResolutionOnZoom) {
            // Compute effective rate after decimation
            int factor = Math.max(1, sampleRate / Math.max(1, 2 * desiredBw));
            int effRate = sampleRate / factor;
            // Target ~1.0 Hz/bin by default
            double targetHzPerBin = 1.0;
            int desiredFft = nextPow2((int) Math.ceil(effRate / Math.max(1e-6, targetHzPerBin)));
            // Clamp FFT size
            int minFft = 2048;
            int maxFft = 32768; // default cap; can be made configurable later
            if (desiredFft < minFft) desiredFft = minFft;
            if (desiredFft > maxFft) desiredFft = maxFft;
            this.fftSize = desiredFft;
            // Increase overlap for smoother waterfall when narrowed
            if (overlapFactor < 4) this.overlapFactor = 4;
        }
    }

    private int nextPow2(int x) {
        if (x <= 1) return 1;
        int n = x - 1;
        n |= n >> 1;
        n |= n >> 2;
        n |= n >> 4;
        n |= n >> 8;
        n |= n >> 16;
        return n + 1;
    }

    private void captureLoop() {
        int frameSize = channels * 2;
        byte[] readBuffer = new byte[0];

        while (running) {
            // Determine how much to read based on FFT size, overlap, and FPS target
            int currentFftSize = fftSize;
            int currentOverlap = Math.max(1, overlapFactor);
            int hopSize = Math.max(1, currentFftSize / currentOverlap);

            int targetFactor = 1;
            if (processingBandwidth > 0 && sampleRate > 0) {
                targetFactor = sampleRate / (2 * processingBandwidth);
                if (targetFactor < 1) targetFactor = 1;
            }

            int currentTargetFps = targetFps;
            if (currentTargetFps < 1) currentTargetFps = 1;

            int effectiveRate = sampleRate / Math.max(1, targetFactor);

            // Cap FPS to the fastest rate possible given the current resolution and overlap:
            int maxFpsByResolution = Math.max(1, effectiveRate / Math.max(1, hopSize));
            // Determine if cap is active compared to user-requested targetFps
            fpsCapActive = targetFps > maxFpsByResolution;
            if (currentTargetFps > maxFpsByResolution) currentTargetFps = maxFpsByResolution;

            // Read small chunks to keep updates frequent, honoring the capped FPS
            // CPU FFT buffering happens in processingLoop; here we just pace IO.
            int samplesPerFrame = Math.max(1, effectiveRate / currentTargetFps);
            // Minimum decimated samples per frame (reduced to avoid huge raw reads at high decimation)
            if (samplesPerFrame < 128) samplesPerFrame = 128;

            // Convert to raw domain and cap raw samples per frame to avoid spikes when targetFactor is large
            int maxRawSamplesPerFrame = 8192; // safety cap; tweak if needed
            int targetRawSamples = samplesPerFrame * Math.max(1, targetFactor);
            if (targetRawSamples > maxRawSamplesPerFrame) targetRawSamples = maxRawSamplesPerFrame;

            // Align to factor
            int rawReadSize = (targetRawSamples / Math.max(1, targetFactor)) * Math.max(1, targetFactor);
            if (rawReadSize < Math.max(1, targetFactor)) rawReadSize = Math.max(1, targetFactor);

            int rawBytesToRead = rawReadSize * frameSize;

            if (readBuffer.length != rawBytesToRead) {
                readBuffer = new byte[rawBytesToRead];
            }

            // Blocking read from hardware
            int bytesRead = targetDataLine.read(readBuffer, 0, readBuffer.length);

            if (bytesRead > 0) {
                int framesRead = bytesRead / frameSize;
                float[] rawSamples = new float[framesRead];

                // Convert bytes to floats
                for (int i = 0; i < framesRead; i++) {
                    double sampleVal;
                    if (channels == 2) {
                        int idx = i * 4;
                        short left = (short)((readBuffer[idx+1] << 8) | (readBuffer[idx] & 0xFF));
                        short right = (short)((readBuffer[idx+3] << 8) | (readBuffer[idx+2] & 0xFF));
                        sampleVal = (left + right) / 2.0;
                    } else {
                        int idx = i * 2;
                        sampleVal = (short)((readBuffer[idx+1] << 8) | (readBuffer[idx] & 0xFF));
                    }
                    rawSamples[i] = (float) (sampleVal / 32768.0);
                }

                // Notify audio listeners (Raw Audio)
                for (Consumer<float[]> listener : audioListeners) {
                    listener.accept(rawSamples);
                }

                // Hand off to Processing Thread
                try {
                    audioQueue.put(rawSamples);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void processingLoop() {
        int currentFftSize = fftSize;
        int currentOverlap = Math.max(1, overlapFactor);
        int hopSize = Math.max(1, currentFftSize / currentOverlap);

        // Ring buffers for decimated samples (real or complex)
        float[] ring = new float[currentFftSize];
        float[] ringI = new float[currentFftSize];
        float[] ringQ = new float[currentFftSize];
        int rbWrite = 0;
        int samplesSinceLastFft = 0;

        // Zero-padding scheme: only window a fraction of the FFT and pad the rest with zeros
        int actualSamples = Math.max(1024, currentFftSize / 4);
        if (actualSamples > currentFftSize) actualSamples = currentFftSize; // safety
        double[] window = makeHannWindow(actualSamples);

        org.example.Decimator decimator = null;
        int currentFactor = 1;

        while (running) {
            try {
                // Wait for data
                float[] rawSamples = audioQueue.poll(100, TimeUnit.MILLISECONDS);
                if (rawSamples == null) continue;

                // Check for config changes
                if (fftSize != currentFftSize || currentOverlap != Math.max(1, overlapFactor)) {
                    currentFftSize = fftSize;
                    currentOverlap = Math.max(1, overlapFactor);
                    hopSize = Math.max(1, currentFftSize / currentOverlap);
                    ring = new float[currentFftSize];
                    rbWrite = 0;
                    samplesSinceLastFft = 0;
                    // Recompute zero-padding parameters
                    actualSamples = Math.max(1024, currentFftSize / 4);
                    if (actualSamples > currentFftSize) actualSamples = currentFftSize;
                    window = makeHannWindow(actualSamples);
                }

                int targetFactor = 1;
                if (processingBandwidth > 0 && sampleRate > 0) {
                    targetFactor = sampleRate / (2 * processingBandwidth);
                    if (targetFactor < 1) targetFactor = 1;
                }

                if (targetFactor != currentFactor || (targetFactor > 1 && decimator == null)) {
                    currentFactor = targetFactor;
                    if (currentFactor > 1) {
                        decimator = new org.example.Decimator(currentFactor);
                    } else {
                        decimator = null;
                    }
                }

                // Recompute pacing based on effective sample rate and target FPS using sliding window
                int effectiveRate = sampleRate / Math.max(1, currentFactor);
                int hopByOverlap = Math.max(1, currentFftSize / Math.max(1, currentOverlap));
                int desiredFps = Math.max(1, targetFps);
                int hopByFps = Math.max(1, Math.round(effectiveRate / (float) desiredFps));
                // Choose the smaller hop to satisfy FPS when possible; overlap acts as a ceiling on how much we can slide
                hopSize = Math.max(1, Math.min(hopByOverlap, hopByFps));
                int maxFpsByOverlap = Math.max(1, effectiveRate / hopByOverlap);
                fpsCapActive = targetFps > maxFpsByOverlap;

                // Decimate
                float[] processedSamples = (decimator != null) ? decimator.process(rawSamples) : rawSamples;
                int newSamplesCount = processedSamples.length;
                if (newSamplesCount == 0) continue;

                // Bulk-insert samples into the ring buffer (faster than per-sample loop)
                int samplesToCopy = newSamplesCount;
                int sourceOffset = 0;
                while (samplesToCopy > 0) {
                    int spaceAtEndOfRing = currentFftSize - rbWrite;
                    int chunk = Math.min(samplesToCopy, spaceAtEndOfRing);
                    System.arraycopy(processedSamples, sourceOffset, ring, rbWrite, chunk);

                    rbWrite = (rbWrite + chunk) % currentFftSize;
                    sourceOffset += chunk;
                    samplesToCopy -= chunk;
                }

                // Track total samples accumulated
                samplesSinceLastFft += newSamplesCount;

                // Trigger as many FFTs as the accumulated samples allow
                while (samplesSinceLastFft >= hopSize && spectrumListener != null) {
                    samplesSinceLastFft -= hopSize;

                    // Obtain or create fftData buffer
                    double[] fftData = fftBufferPool.poll();
                    if (fftData == null || fftData.length != currentFftSize * 2) {
                        fftData = new double[currentFftSize * 2];
                    }

                    // Zero-Padding extraction: use only 'actualSamples' of real audio, pad the rest with zeros
                    int readIdx = (rbWrite - actualSamples + currentFftSize) % currentFftSize;
                    for (int k = 0; k < currentFftSize; k++) {
                        if (k < actualSamples) {
                            fftData[k] = ring[readIdx] * window[k];
                            readIdx = (readIdx + 1) % currentFftSize;
                        } else {
                            fftData[k] = 0.0;
                        }
                    }

                    // Execute FFT either inline (sequential) or via executor (multithreaded)
                    if (fftWorkerThreads <= 1) {
                        // We are already on the dedicated processingThread, so just do the math here sequentially!
                        try {
                            java.util.Map<Integer, DoubleFFT_1D> map = FFT_PLANS.get();
                            DoubleFFT_1D plan = map.get(currentFftSize);
                            if (plan == null) {
                                plan = new DoubleFFT_1D(currentFftSize);
                                map.put(currentFftSize, plan);
                            }
                            plan.realForward(fftData);

                            // Compute magnitude (N/2 bins)
                            double[] magnitude = new double[currentFftSize / 2];
                            for (int b = 0; b < magnitude.length; b++) {
                                double re = fftData[2 * b];
                                double im = fftData[2 * b + 1];
                                magnitude[b] = Math.sqrt(re * re + im * im);
                            }

                            spectrumListener.accept(magnitude);
                        } finally {
                            // recycle buffer
                            fftBufferPool.offer(fftData);
                        }
                    } else {
                        final int jobFftSize = currentFftSize;
                        double[] jobData = fftData; // capture for lambda
                        fftExecutor.submit(() -> {
                            try {
                                // Thread-local FFT plan per size
                                java.util.Map<Integer, DoubleFFT_1D> map = FFT_PLANS.get();
                                DoubleFFT_1D plan = map.get(jobFftSize);
                                if (plan == null) {
                                    plan = new DoubleFFT_1D(jobFftSize);
                                    map.put(jobFftSize, plan);
                                }
                                plan.realForward(jobData);

                                // Compute magnitude (N/2 bins)
                                double[] magnitude = new double[jobFftSize / 2];
                                for (int b = 0; b < magnitude.length; b++) {
                                    double re = jobData[2 * b];
                                    double im = jobData[2 * b + 1];
                                    magnitude[b] = Math.sqrt(re * re + im * im);
                                }

                                spectrumListener.accept(magnitude);
                            } finally {
                                // recycle buffer
                                fftBufferPool.offer(jobData);
                            }
                        });
                    }
                }

                // If queue backs up severely, skip scheduling to catch up
                if (audioQueue.size() > 8) {
                    samplesSinceLastFft = 0; // resync on next
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // Thread-local cache of FFT plans keyed by size
    private static final ThreadLocal<java.util.Map<Integer, DoubleFFT_1D>> FFT_PLANS = ThreadLocal.withInitial(java.util.HashMap::new);

    private double[] makeHannWindow(int size) {
        double[] window = new double[size];
        for (int i = 0; i < size; i++) {
            window[i] = 0.5 * (1 - Math.cos(2 * Math.PI * i / (size - 1)));
        }
        return window;
    }

    public boolean isFpsCapActive() {
        return fpsCapActive;
    }

    public int getSampleRate() {
        return sampleRate;
    }
}

