package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * USAGE DISPLAY numeric codec (zoned decimal, EBCDIC). Each digit is X'Fn'.
 * The sign is an overpunched zone on the last byte (C/A/E/F positive, D/B negative) unless
 * SIGN LEADING and/or SEPARATE is specified. Unsigned fields are written with zone F.
 */
public final class ZonedDecimal {
    public enum Sign { NONE, TRAILING, LEADING, TRAILING_SEPARATE, LEADING_SEPARATE }

    private static final int EBCDIC_PLUS = 0x4E;
    private static final int EBCDIC_MINUS = 0x60;

    private ZonedDecimal() {
    }

    public static int byteLength(int digits, Sign sign) {
        return digits + (sign == Sign.LEADING_SEPARATE || sign == Sign.TRAILING_SEPARATE ? 1 : 0);
    }

    public static BigDecimal decode(byte[] buf, int offset, int digits, int scale, Sign sign) {
        int start = offset + (sign == Sign.LEADING_SEPARATE ? 1 : 0);
        boolean negative = false;
        BigInteger value = BigInteger.ZERO;
        long acc = 0;
        int accDigits = 0;
        for (int i = 0; i < digits; i++) {
            int b = buf[start + i] & 0xFF;
            int zone = b >>> 4;
            int digit = b & 0x0F;
            boolean overpunch = (sign == Sign.TRAILING && i == digits - 1) || (sign == Sign.LEADING && i == 0);
            if (overpunch) {
                negative = switch (zone) {
                    case 0x0C, 0x0A, 0x0E, 0x0F -> false;
                    case 0x0D, 0x0B -> true;
                    default -> throw invalid(buf, offset, digits, sign);
                };
            } else if (zone != 0x0F) {
                throw invalid(buf, offset, digits, sign);
            }
            if (digit > 9) {
                throw invalid(buf, offset, digits, sign);
            }
            acc = acc * 10 + digit;
            if (++accDigits == 18) {
                value = value.multiply(BigInteger.TEN.pow(18)).add(BigInteger.valueOf(acc));
                acc = 0;
                accDigits = 0;
            }
        }
        value = value.multiply(BigInteger.TEN.pow(accDigits)).add(BigInteger.valueOf(acc));
        if (sign == Sign.LEADING_SEPARATE || sign == Sign.TRAILING_SEPARATE) {
            int signByte = buf[sign == Sign.LEADING_SEPARATE ? offset : offset + digits] & 0xFF;
            if (signByte == EBCDIC_MINUS) {
                negative = true;
            } else if (signByte != EBCDIC_PLUS) {
                throw invalid(buf, offset, digits, sign);
            }
        }
        return new BigDecimal(negative ? value.negate() : value, scale);
    }

    public static void encode(BigDecimal value, byte[] buf, int offset, int digits, int scale, Sign sign) {
        boolean signed = sign != Sign.NONE;
        BigDecimal fitted = Assign.fit(value, digits, scale, signed, false);
        String text = fitted.unscaledValue().abs().toString();
        int start = offset + (sign == Sign.LEADING_SEPARATE ? 1 : 0);
        int pad = digits - text.length();
        boolean negative = fitted.signum() < 0;
        for (int i = 0; i < digits; i++) {
            int digit = i < pad ? 0 : text.charAt(i - pad) - '0';
            int zone = 0x0F;
            if ((sign == Sign.TRAILING && i == digits - 1) || (sign == Sign.LEADING && i == 0)) {
                zone = negative ? 0x0D : 0x0C;
            }
            buf[start + i] = (byte) ((zone << 4) | digit);
        }
        if (sign == Sign.LEADING_SEPARATE) {
            buf[offset] = (byte) (negative ? EBCDIC_MINUS : EBCDIC_PLUS);
        } else if (sign == Sign.TRAILING_SEPARATE) {
            buf[offset + digits] = (byte) (negative ? EBCDIC_MINUS : EBCDIC_PLUS);
        }
    }

    /** COBOL class test {@code IF field NUMERIC}. */
    public static boolean isValid(byte[] buf, int offset, int digits, Sign sign) {
        try {
            decode(buf, offset, digits, 0, sign);
            return true;
        } catch (CobolDataException e) {
            return false;
        }
    }

    private static CobolDataException invalid(byte[] buf, int offset, int digits, Sign sign) {
        return new CobolDataException("invalid zoned decimal " + Hex.of(buf, offset, byteLength(digits, sign)));
    }
}
