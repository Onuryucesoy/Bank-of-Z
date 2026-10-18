package com.ibm.bankofz.core.codec;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * PIC 9 / PIC S9 USAGE DISPLAY (zoned decimal) codec in EBCDIC. Each digit is
 * one byte X'F0'..X'F9'. For signed fields the sign is overpunched into the
 * zone of the last byte (C positive, D negative, F accepted as unsigned
 * positive). The implied decimal point (V) occupies no storage.
 */
public final class ZonedDecimal {

    private ZonedDecimal() {
    }

    public static byte[] encode(BigDecimal value, int digits, int scale, boolean signed) {
        BigInteger unscaled = Numeric.unscaled(value, digits, scale, signed);
        String d = Numeric.leftPad(unscaled.abs().toString(), digits);
        byte[] out = new byte[digits];
        for (int i = 0; i < digits; i++) {
            out[i] = (byte) (0xF0 | (d.charAt(i) - '0'));
        }
        if (signed) {
            int zone = unscaled.signum() < 0 ? 0xD0 : 0xC0;
            out[digits - 1] = (byte) (zone | (out[digits - 1] & 0x0F));
        }
        return out;
    }

    public static BigDecimal decode(byte[] data, int offset, int digits, int scale) {
        StringBuilder sb = new StringBuilder(digits + 1);
        boolean negative = false;
        for (int i = 0; i < digits; i++) {
            int b = data[offset + i] & 0xFF;
            int zone = b >> 4;
            int digit = b & 0x0F;
            if (digit > 9) {
                throw new CodecException(String.format("invalid zoned digit byte %02X at offset %d", b, offset + i));
            }
            boolean last = i == digits - 1;
            if (zone != 0x0F && !(last && (zone == 0x0C || zone == 0x0D))) {
                throw new CodecException(String.format("invalid zoned byte %02X at offset %d", b, offset + i));
            }
            if (last && zone == 0x0D) {
                negative = true;
            }
            sb.append((char) ('0' + digit));
        }
        BigInteger unscaled = new BigInteger(sb.toString());
        return new BigDecimal(negative ? unscaled.negate() : unscaled, scale);
    }

    private static final byte PLUS = 0x4E;
    private static final byte MINUS = 0x60;

    /** {@code SIGN [LEADING|TRAILING] SEPARATE}: one extra EBCDIC '+'/'-' byte. */
    public static byte[] encodeSignSeparate(BigDecimal value, int digits, int scale, boolean leading) {
        byte[] body = encode(value.abs(), digits, scale, false);
        byte[] out = new byte[digits + 1];
        byte sign = value.signum() < 0 ? MINUS : PLUS;
        System.arraycopy(body, 0, out, leading ? 1 : 0, digits);
        out[leading ? 0 : digits] = sign;
        return out;
    }

    public static BigDecimal decodeSignSeparate(byte[] data, int offset, int digits, int scale, boolean leading) {
        byte sign = data[leading ? offset : offset + digits];
        if (sign != PLUS && sign != MINUS) {
            throw new CodecException(String.format("invalid separate sign byte %02X", sign & 0xFF));
        }
        BigDecimal v = decode(data, leading ? offset + 1 : offset, digits, scale);
        return sign == MINUS ? v.negate() : v;
    }
}
