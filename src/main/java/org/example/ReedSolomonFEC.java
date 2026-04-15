package org.example;

import com.google.zxing.common.reedsolomon.GenericGF;
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder;
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder;

/**
 * Reed–Solomon FEC wrapper for RS(255,223) over GF(256).
 * Uses ZXing's lightweight RS implementation.
 */
public class ReedSolomonFEC {

    // RS(255, 223) parameters
    public static final int BLOCK_SIZE = 255;
    public static final int DATA_SIZE = 223;
    public static final int PARITY_SIZE = BLOCK_SIZE - DATA_SIZE; // 32

    private static final GenericGF FIELD = GenericGF.QR_CODE_FIELD_256; // primitive poly 0x011D
    private static final ReedSolomonEncoder ENCODER = new ReedSolomonEncoder(FIELD);
    private static final ReedSolomonDecoder DECODER = new ReedSolomonDecoder(FIELD);

    /**
     * Apply RS(255,223) FEC: chunk input into 223B, zero-pad last, append 32 parity bytes.
     */
    public static byte[] applyFEC(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return new byte[0];
        }
        int numBlocks = (int) Math.ceil(payload.length / (double) DATA_SIZE);
        byte[] out = new byte[numBlocks * BLOCK_SIZE];

        for (int i = 0; i < numBlocks; i++) {
            int payloadOffset = i * DATA_SIZE;
            int copyLen = Math.min(DATA_SIZE, payload.length - payloadOffset);

            int[] block = new int[BLOCK_SIZE]; // ZXing expects ints 0..255
            // copy data (zero-padded by default)
            for (int j = 0; j < copyLen; j++) {
                block[j] = payload[payloadOffset + j] & 0xFF;
            }
            // Encode: ENCODER.encode expects array of length data+parity
            ENCODER.encode(block, PARITY_SIZE);

            // write out as bytes
            int outOffset = i * BLOCK_SIZE;
            for (int j = 0; j < BLOCK_SIZE; j++) {
                out[outOffset + j] = (byte) (block[j] & 0xFF);
            }
        }
        return out;
    }

    /**
     * Remove RS(255,223) FEC: correct each 255B block and return concatenated data bytes (still contains any zero-pad from the final block caller should trim using a higher-layer length field).
     */
    public static byte[] removeFEC(byte[] received) {
        if (received == null || received.length == 0) {
            return new byte[0];
        }
        if (received.length % BLOCK_SIZE != 0) {
            throw new IllegalArgumentException("Corrupted frame: length not multiple of 255");
        }
        int numBlocks = received.length / BLOCK_SIZE;
        byte[] recovered = new byte[numBlocks * DATA_SIZE];

        for (int i = 0; i < numBlocks; i++) {
            int inOffset = i * BLOCK_SIZE;
            int[] block = new int[BLOCK_SIZE];
            for (int j = 0; j < BLOCK_SIZE; j++) {
                block[j] = received[inOffset + j] & 0xFF;
            }
            try {
                DECODER.decode(block, PARITY_SIZE);
            } catch (Exception e) {
                // Too many errors to correct
                throw new IllegalArgumentException("RS decode failed: block " + i + ": " + e.getMessage(), e);
            }
            // Copy only data part
            int outOffset = i * DATA_SIZE;
            for (int j = 0; j < DATA_SIZE; j++) {
                recovered[outOffset + j] = (byte) (block[j] & 0xFF);
            }
        }
        return recovered;
    }
}
