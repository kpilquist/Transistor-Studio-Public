package org.example;

/**
 * Transistor Studio - Lane B: Industrial Math Module
 * Phase 2: OTFS (Orthogonal Time Frequency Space) Modulator
 * Objective: Implement ISFFT and Heisenberg Transform.
 *
 * Mathematical Flow:
 * Symbols (Delay-Doppler) -> [ISFFT] -> Grid (Time-Frequency) -> [Heisenberg] -> Audio (Time)
 */
public class OTFS_Engine {

    // Configuration: The "Resolution" of our radar/data grid
    // M = Delay Bins (Subcarriers). Higher = Better multipath handling.
    // N = Doppler Bins (Time Slots). Higher = Better speed/fading handling.
    private final int M;
    private final int N;

    public OTFS_Engine(int m, int n) {
        this.M = m;
        this.N = n;
    }

    /**
     * The Core Pipeline: Maps data symbols to the raw audio waveform.
     * @param symbols 2D Grid [M][N] of Complex numbers (QAM symbols)
     * @return Complex[] The 1D time-domain signal ready for TX
     */
    public Complex[] generateWaveform(Complex[][] symbols) {
        System.out.println(">> Lane B: Starting OTFS Modulation...");

        // Basic validations
        if (symbols == null || symbols.length != M || symbols[0].length != N) {
            throw new IllegalArgumentException("Symbols grid must be of size MxN");
        }

        // Step 1: The ISFFT (Inverse Symplectic Finite Fourier Transform)
        // Transforms Delay-Doppler domain -> Time-Frequency domain
        Complex[][] tfGrid = inverseSymplecticFFT(symbols);

        // Step 2: The Heisenberg Transform
        // Transforms Time-Frequency domain -> Time Domain (Actual Waveform)
        Complex[] timeSignal = heisenbergTransform(tfGrid);

        System.out.println(">> Lane B: Waveform Generated. Samples: " + timeSignal.length);
        return timeSignal;
    }

    /**
     * ISFFT Implementation
     * Logic: Inverse FFT along Doppler axis (N), then Forward FFT along Delay axis (M).
     * Notes:
     *  - This implementation assumes radix-2 lengths for both axes (required by the FFT below).
     */
    private Complex[][] inverseSymplecticFFT(Complex[][] ddGrid) {
        Complex[][] tfGrid = new Complex[M][N];

        // 1. Inverse FFT along Doppler (Columns / N-axis)
        // This converts Doppler shifts into Time variations
        Complex[][] intermediate = new Complex[M][N];
        for (int m = 0; m < M; m++) {
            // IFFT across the N-sized Doppler dimension within each delay bin
            intermediate[m] = FFT.ifft(ddGrid[m]);
        }

        // 2. Forward FFT along Delay (Rows / M-axis)
        // Convert Delay taps into Frequency subcarriers (operate per time index n)
        for (int n = 0; n < N; n++) {
            Complex[] col = new Complex[M];
            for (int m = 0; m < M; m++) {
                col[m] = intermediate[m][n];
            }
            Complex[] fftCol = FFT.fft(col);
            for (int m = 0; m < M; m++) {
                tfGrid[m][n] = fftCol[m];
            }
        }

        return tfGrid;
    }

    /**
     * Heisenberg Transform Implementation
     * Logic: Effectively an OFDM Modulator.
     * Performs an IFFT on the Frequency axis (M) for each Time slot (N),
     * and serializes the time-domain symbols.
     */
    private Complex[] heisenbergTransform(Complex[][] tfGrid) {
        Complex[] output = new Complex[M * N];

        for (int n = 0; n < N; n++) {
            // Extract the frequency column for this time slot
            Complex[] freqColumn = new Complex[M];
            for (int m = 0; m < M; m++) {
                freqColumn[m] = tfGrid[m][n];
            }

            // IFFT to get time samples for this symbol
            Complex[] timeSamples = FFT.ifft(freqColumn);

            // Serialize into the output buffer
            for (int m = 0; m < M; m++) {
                output[(n * M) + m] = timeSamples[m];
            }
        }

        return output;
    }

    /**
     * Inverse of the Heisenberg transform: recover Time-Frequency grid from time samples.
     * Split the time-domain stream into N blocks of length M and FFT each block.
     */
    private Complex[][] inverseHeisenbergTransform(Complex[] time) {
        if (time == null || time.length != M * N) {
            throw new IllegalArgumentException("Time signal length must be M*N");
        }
        Complex[][] tfGrid = new Complex[M][N];
        for (int n = 0; n < N; n++) {
            // Take block n
            Complex[] block = new Complex[M];
            for (int m = 0; m < M; m++) {
                block[m] = time[n * M + m];
            }
            // FFT to return to frequency for this time slot
            Complex[] freqCol = FFT.fft(block);
            for (int m = 0; m < M; m++) {
                tfGrid[m][n] = freqCol[m];
            }
        }
        return tfGrid;
    }

    /**
     * Symplectic FFT (inverse of inverseSymplecticFFT): recovers Delay-Doppler grid from TF grid.
     * Logic: IFFT along Delay (M) for each time index, then FFT along Doppler (N) for each delay bin.
     */
    private Complex[][] symplecticFFT(Complex[][] tfGrid) {
        Complex[][] intermediate = new Complex[M][N];
        // 1) IFFT along Delay (rows of length M) per time n
        for (int n = 0; n < N; n++) {
            Complex[] col = new Complex[M];
            for (int m = 0; m < M; m++) col[m] = tfGrid[m][n];
            Complex[] ifftCol = FFT.ifft(col);
            for (int m = 0; m < M; m++) intermediate[m][n] = ifftCol[m];
        }
        // 2) FFT along Doppler (columns of length N) per delay m
        Complex[][] ddGrid = new Complex[M][N];
        for (int m = 0; m < M; m++) {
            Complex[] row = new Complex[N];
            for (int n = 0; n < N; n++) row[n] = intermediate[m][n];
            Complex[] fftRow = FFT.fft(row);
            for (int n = 0; n < N; n++) ddGrid[m][n] = fftRow[n];
        }
        return ddGrid;
    }

    /**
     * Full demodulation pipeline: Time -> TF (inverse Heisenberg) -> Delay-Doppler (Symplectic FFT)
     */
    public Complex[][] demodulateToDelayDoppler(Complex[] time) {
        Complex[][] tf = inverseHeisenbergTransform(time);
        return symplecticFFT(tf);
    }

    // --- Minimal helper classes for the Math ---

    /**
     * Minimal Complex Number Class
     */
    public static class Complex {
        public final double re;
        public final double im;

        public Complex(double re, double im) {
            this.re = re;
            this.im = im;
        }

        public Complex add(Complex b) { return new Complex(this.re + b.re, this.im + b.im); }
        public Complex sub(Complex b) { return new Complex(this.re - b.re, this.im - b.im); }
        public Complex mul(Complex b) { return new Complex(this.re * b.re - this.im * b.im, this.re * b.im + this.im * b.re); }
        public Complex scale(double alpha) { return new Complex(this.re * alpha, this.im * alpha); }

        @Override
        public String toString() { return String.format("(%.2f, %.2fi)", re, im); }
    }

    /**
     * Static FFT Utility (Cooley-Tukey Radix-2)
     * Note: For production, replace this with JTransforms or Apache Commons Math.
     */
    public static class FFT {
        public static Complex[] fft(Complex[] x) {
            int n = x.length;
            if (n == 1) return new Complex[] { x[0] };
            if ((n & (n - 1)) != 0) throw new IllegalArgumentException("FFT length must be a power of 2");

            Complex[] even = new Complex[n / 2];
            Complex[] odd = new Complex[n / 2];
            for (int i = 0; i < n / 2; i++) {
                even[i] = x[2 * i];
                odd[i] = x[2 * i + 1];
            }

            Complex[] q = fft(even);
            Complex[] r = fft(odd);
            Complex[] y = new Complex[n];

            for (int k = 0; k < n / 2; k++) {
                double kth = -2 * k * Math.PI / n;
                Complex wk = new Complex(Math.cos(kth), Math.sin(kth));
                Complex t = wk.mul(r[k]);
                y[k] = q[k].add(t);
                y[k + n / 2] = q[k].sub(t);
            }
            return y;
        }

        public static Complex[] ifft(Complex[] x) {
            int n = x.length;
            // Conjugate input
            Complex[] y = new Complex[n];
            for (int i = 0; i < n; i++) y[i] = new Complex(x[i].re, -x[i].im);
            // Forward FFT
            y = fft(y);
            // Conjugate output and scale by N
            Complex[] res = new Complex[n];
            for (int i = 0; i < n; i++) {
                res[i] = new Complex(y[i].re / n, -y[i].im / n);
            }
            return res;
        }
    }

    // --- Main Driver for simple sanity testing ---
    public static void main(String[] args) {
        // Setup a small grid for testing (powers of two for this simple FFT)
        // M=4 (Subcarriers), N=4 (Time slots)
        OTFS_Engine otfs = new OTFS_Engine(4, 4);

        // Create a dummy symbol grid (e.g., all zeros except one "Pilot" symbol)
        // In Delay-Doppler, a single spike is a perfect pilot.
        Complex[][] symbols = new Complex[4][4];
        for (int m = 0; m < 4; m++) {
            for (int n = 0; n < 4; n++) {
                symbols[m][n] = new Complex(0, 0);
            }
        }
        symbols[1][1] = new Complex(1, 0); // Inject a pilot at Delay=1, Doppler=1

        // Run the engine
        Complex[] waveform = otfs.generateWaveform(symbols);

        // Print first few samples
        System.out.println("Generated Waveform Data (First 8 samples):");
        for (int i = 0; i < Math.min(8, waveform.length); i++) {
            System.out.println("Sample " + i + ": " + waveform[i]);
        }
    }
}
