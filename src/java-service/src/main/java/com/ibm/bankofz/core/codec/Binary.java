package com.ibm.bankofz.core.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP / BINARY / COMP-4 / COMP-5 codec: big-endian two's complement in 2, 4
 * or 8 bytes. COMP-5 uses the full native range; COMP is limited to the PIC
 * digit count (TRUNC(STD)), which callers enforce via {@link #encode}.
 */
public final class Binary {

    private Binary() {
    }

    public static int byteLength(int digits) {
        if (digits <= 4) {
            return 2;
        }
        if (digits <= 9) {
            return 4;
        }
        return 8;
    }

    public static byte[] encode(BigDecimal value, int digits, int scale, boolean signed, boolean nativeRange) {
        int len = byteLength(digits);
        BigInteger unscaled = nativeRange
                ? value.setScale(scale).unscaledValue()
                : Numeric.unscaled(value, digits, scale, signed);
        if (!signed && unscaled.signum() < 0) {
            throw new CodecException(value + " is negative but the PIC is unsigned");
        }
        int bits = len * 8 - (signed ? 1 : 0);
        if (unscaled.abs().bitLength() > bits) {
            throw new CodecException(value + " does not fit in " + len + " binary bytes");
        }
        byte[] raw = unscaled.toByteArray();
        byte[] out = new byte[len];
        byte fill = unscaled.signum() < 0 ? (byte) 0xFF : 0;
        for (int i = 0; i < len; i++) {
            int src = raw.length - len + i;
            out[i] = src >= 0 ? raw[src] : fill;
        }
        return out;
    }

    public static BigDecimal decode(byte[] data, int offset, int digits, int scale, boolean signed) {
        int len = byteLength(digits);
        byte[] slice = new byte[len];
        System.arraycopy(data, offset, slice, 0, len);
        BigInteger v = signed ? new BigInteger(slice) : new BigInteger(1, slice);
        return new BigDecimal(v, scale);
    }
}
