package org.example;

import java.util.Random;

/**
 * OTFS loopback test mode.
 *
 * Generates random QPSK symbols on an MxN delay–doppler grid, modulates using OTFS,
 * (optionally) adds AWGN noise, then demodulates and measures EVM and SER.
 *
 * Usage:
 *   java -cp target/classes;lib/* org.example.OTFSLoopback [M] [N] [SNR_dB] [seed]
 * Defaults: M=16, N=16, SNR=40 dB (almost noiseless), seed=1234
 */
public class OTFSLoopback {

    public static void main(String[] args) {
        int M = parseOr(args, 0, 16);
        int N = parseOr(args, 1, 16);
        double snrDb = parseOrDouble(args, 2, 40.0);
        long seed = (long) parseOr(args, 3, 1234);

        System.out.println("[OTFS] Loopback start M=" + M + " N=" + N + " SNR_dB=" + snrDb + " seed=" + seed);

        if (!isPowerOfTwo(M) || !isPowerOfTwo(N)) {
            System.out.println("[OTFS] ERROR: This simple FFT requires M and N to be powers of two.");
            System.exit(2);
            return;
        }

        OTFS_Engine engine = new OTFS_Engine(M, N);

        // Generate random QPSK symbols
        OTFS_Engine.Complex[][] ddGrid = new OTFS_Engine.Complex[M][N];
        Random rnd = new Random(seed);
        for (int m = 0; m < M; m++) {
            for (int n = 0; n < N; n++) {
                int bits = rnd.nextInt(4);
                ddGrid[m][n] = qpskSymbol(bits);
            }
        }

        // Modulate
        OTFS_Engine.Complex[] tx = engine.generateWaveform(ddGrid);

        // Add AWGN according to SNR
        OTFS_Engine.Complex[] rx = addAwgn(tx, snrDb, rnd);

        // Demodulate back to delay–doppler
        OTFS_Engine.Complex[][] ddRx = engine.demodulateToDelayDoppler(rx);

        // Normalize (note: with unitary vs non-unitary FFTs, there can be a global gain factor).
        // We'll estimate a single complex scalar that best maps ddRx to ddGrid in least-squares sense (common phase/gain).
        OTFS_Engine.Complex scale = lsBestScalar(ddRx, ddGrid);
        double num = 0.0, den = 0.0;
        int symErrors = 0;
        int total = M * N;
        for (int m = 0; m < M; m++) {
            for (int n = 0; n < N; n++) {
                OTFS_Engine.Complex ref = ddGrid[m][n];
                OTFS_Engine.Complex est = mul(ddRx[m][n], scale);
                double errRe = est.re - ref.re;
                double errIm = est.im - ref.im;
                num += errRe * errRe + errIm * errIm;
                den += ref.re * ref.re + ref.im * ref.im;
                // Hard decision QPSK and compare
                int refBits = qpskDemap(ref);
                int estBits = qpskDemap(est);
                if (refBits != estBits) symErrors++;
            }
        }
        double evmRms = Math.sqrt(num / Math.max(1e-12, den));
        double ser = symErrors / (double) total;
        System.out.printf("[OTFS] Results: EVM_rms=%.6f (%.2f%%), SER=%.6f (%d/%d)\n", evmRms, 100.0 * evmRms, ser, symErrors, total);
        System.out.println("[OTFS] Loopback complete.");
    }

    private static boolean isPowerOfTwo(int x) { return x > 0 && (x & (x - 1)) == 0; }

    private static int parseOr(String[] a, int idx, int def) {
        try { return (a != null && a.length > idx) ? Integer.parseInt(a[idx]) : def; } catch (Exception e) { return def; }
    }
    private static double parseOrDouble(String[] a, int idx, double def) {
        try { return (a != null && a.length > idx) ? Double.parseDouble(a[idx]) : def; } catch (Exception e) { return def; }
    }

    // QPSK mapping: 00 -> ( +1,+1 ), 01 -> ( -1,+1 ), 11 -> ( -1,-1 ), 10 -> ( +1,-1 ) (Gray)
    private static OTFS_Engine.Complex qpskSymbol(int twoBits) {
        switch (twoBits & 0b11) {
            case 0b00: return new OTFS_Engine.Complex(1, 1);
            case 0b01: return new OTFS_Engine.Complex(-1, 1);
            case 0b11: return new OTFS_Engine.Complex(-1, -1);
            case 0b10: return new OTFS_Engine.Complex(1, -1);
        }
        return new OTFS_Engine.Complex(1, 1);
    }
    private static int qpskDemap(OTFS_Engine.Complex s) {
        int b0 = s.re >= 0 ? 0 : 1; // MSB decision on I
        int b1 = s.im >= 0 ? 0 : 1; // LSB decision on Q
        // Map back using inverse of mapping above (Gray):
        // I>=0,Q>=0 -> 00; I<0,Q>=0 -> 01; I<0,Q<0 -> 11; I>=0,Q<0 -> 10
        if (b0 == 0 && b1 == 0) return 0b00;
        if (b0 == 1 && b1 == 0) return 0b01;
        if (b0 == 1 && b1 == 1) return 0b11;
        return 0b10; // b0==0 && b1==1
    }

    private static OTFS_Engine.Complex[] addAwgn(OTFS_Engine.Complex[] x, double snrDb, Random rnd) {
        double snrLin = Math.pow(10.0, snrDb / 10.0);
        // Measure average signal power
        double p = 0.0;
        for (OTFS_Engine.Complex c : x) p += c.re * c.re + c.im * c.im;
        p /= x.length;
        // For complex AWGN, each dimension has variance = N0/2, and SNR = Ps / (N0)
        double n0 = p / Math.max(1e-12, snrLin);
        double sigma = Math.sqrt(n0 / 2.0);
        OTFS_Engine.Complex[] y = new OTFS_Engine.Complex[x.length];
        for (int i = 0; i < x.length; i++) {
            double nr = sigma * gaussian(rnd);
            double ni = sigma * gaussian(rnd);
            y[i] = new OTFS_Engine.Complex(x[i].re + nr, x[i].im + ni);
        }
        return y;
    }

    private static double gaussian(Random r) {
        // Box-Muller transform
        double u1 = Math.max(1e-12, r.nextDouble());
        double u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);
    }

    // Least-squares best complex scalar alpha minimizing ||alpha*X - Y||^2 is alpha = (sum X*conj(Y)) / (sum |X|^2)
    private static OTFS_Engine.Complex lsBestScalar(OTFS_Engine.Complex[][] x, OTFS_Engine.Complex[][] y) {
        double numRe = 0.0, numIm = 0.0;
        double den = 0.0;
        int M = x.length;
        int N = x[0].length;
        for (int m = 0; m < M; m++) {
            for (int n = 0; n < N; n++) {
                OTFS_Engine.Complex a = x[m][n];
                OTFS_Engine.Complex b = y[m][n];
                // a * conj(b)
                double cr = a.re * b.re + a.im * b.im;
                double ci = a.im * b.re - a.re * b.im;
                numRe += cr;
                numIm += ci;
                den += a.re * a.re + a.im * a.im;
            }
        }
        if (den < 1e-18) den = 1e-18;
        return new OTFS_Engine.Complex(numRe / den, numIm / den);
    }

    private static OTFS_Engine.Complex mul(OTFS_Engine.Complex a, OTFS_Engine.Complex b) {
        return new OTFS_Engine.Complex(a.re * b.re - a.im * b.im, a.re * b.im + a.im * b.re);
    }
}
