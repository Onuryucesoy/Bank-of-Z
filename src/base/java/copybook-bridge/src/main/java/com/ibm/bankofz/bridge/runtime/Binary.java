package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP / COMP-4 / BINARY and COMP-5 codec: big-endian two's complement (z/Architecture), 2/4/8 bytes.
 * COMP under TRUNC(STD) truncates to the PICTURE digits; COMP-5 truncates only to the storage size.
 */
public final class Binary {
    private Binary() {
    }

    public static int byteLength(int digits) {
        if (digits <= 4) {
            return 2;
        }
        return digits <= 9 ? 4 : 8;
    }

    public static BigDecimal decode(byte[] buf, int offset, int digits, int scale, boolean signed) {
        int len = byteLength(digits);
        byte[] raw = new byte[len];
        System.arraycopy(buf, offset, raw, 0, len);
        BigInteger v = signed ? new BigInteger(raw) : new BigInteger(1, raw);
        return new BigDecimal(v, scale);
    }

    public static void encode(BigDecimal value, byte[] buf, int offset, int digits, int scale,
                              boolean signed, boolean native5) {
        int len = byteLength(digits);
        BigInteger unscaled;
        if (native5) {
            unscaled = value.setScale(scale, java.math.RoundingMode.DOWN).unscaledValue();
            if (!signed) {
                unscaled = unscaled.abs();
            }
        } else {
            unscaled = Assign.fit(value, digits, scale, signed, false).unscaledValue();
        }
        BigInteger wrapped = unscaled.mod(BigInteger.ONE.shiftLeft(len * 8));
        byte[] raw = wrapped.toByteArray();
        for (int i = 0; i < len; i++) {
            int src = raw.length - len + i;
            buf[offset + i] = src >= 0 ? raw[src] : 0;
        }
    }
}
