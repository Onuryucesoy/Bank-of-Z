package com.ibm.bankofz.core.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP-3 (packed decimal) codec. Two digits per byte, sign in the low nibble
 * of the last byte: C/A/E/F positive, D/B negative. Encodes with C (signed)
 * or F (unsigned PIC) as IBM Enterprise COBOL does.
 */
public final class PackedDecimal {

    private PackedDecimal() {
    }

    public static int byteLength(int digits) {
        return digits / 2 + 1;
    }

    public static byte[] encode(BigDecimal value, int digits, int scale, boolean signed) {
        BigInteger unscaled = Numeric.unscaled(value, digits, scale, signed);
        String d = Numeric.leftPad(unscaled.abs().toString(), digits);
        int len = byteLength(digits);
        // An even digit count leaves one leading pad nibble of zero.
        String nibbles = (digits % 2 == 0 ? "0" : "") + d;
        byte[] out = new byte[len];
        for (int i = 0; i < len - 1; i++) {
            out[i] = (byte) (((nibbles.charAt(2 * i) - '0') << 4) | (nibbles.charAt(2 * i + 1) - '0'));
        }
        int sign = !signed ? 0x0F : unscaled.signum() < 0 ? 0x0D : 0x0C;
        out[len - 1] = (byte) (((nibbles.charAt(2 * (len - 1)) - '0') << 4) | sign);
        return out;
    }

    public static BigDecimal decode(byte[] data, int offset, int digits, int scale) {
        int len = byteLength(digits);
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = 0; i < len; i++) {
            int b = data[offset + i] & 0xFF;
            int hi = b >> 4;
            int lo = b & 0x0F;
            checkDigit(hi, offset + i);
            sb.append((char) ('0' + hi));
            if (i < len - 1) {
                checkDigit(lo, offset + i);
                sb.append((char) ('0' + lo));
            } else if (lo < 0x0A) {
                throw new CodecException(String.format("invalid packed sign nibble %X at offset %d", lo, offset + i));
            } else if (lo == 0x0D || lo == 0x0B) {
                sb.insert(0, '-');
            }
        }
        return new BigDecimal(new BigInteger(sb.toString()), scale);
    }

    private static void checkDigit(int nibble, int pos) {
        if (nibble > 9) {
            throw new CodecException(String.format("invalid packed digit nibble %X at offset %d", nibble, pos));
        }
    }
}
