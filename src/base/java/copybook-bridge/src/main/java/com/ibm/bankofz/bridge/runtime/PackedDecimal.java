package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COMP-3 / PACKED-DECIMAL (and PL/I FIXED DECIMAL) codec.
 * Length is digits/2+1 bytes; the low nibble of the last byte is the sign
 * (C/A/E/F positive, D/B negative). COBOL writes C or D for signed fields and F for unsigned ones.
 */
public final class PackedDecimal {
    private PackedDecimal() {
    }

    public static int byteLength(int digits) {
        return digits / 2 + 1;
    }

    public static BigDecimal decode(byte[] buf, int offset, int digits, int scale) {
        int len = byteLength(digits);
        BigInteger value = BigInteger.ZERO;
        long acc = 0;
        int accDigits = 0;
        for (int i = 0; i < len; i++) {
            int b = buf[offset + i] & 0xFF;
            int hi = b >>> 4;
            int lo = b & 0x0F;
            checkDigit(hi, buf, offset, len);
            acc = acc * 10 + hi;
            accDigits++;
            if (i < len - 1) {
                checkDigit(lo, buf, offset, len);
                acc = acc * 10 + lo;
                accDigits++;
            }
            if (accDigits >= 16) {
                value = value.multiply(BigInteger.TEN.pow(accDigits)).add(BigInteger.valueOf(acc));
                acc = 0;
                accDigits = 0;
            }
        }
        value = value.multiply(BigInteger.TEN.pow(accDigits)).add(BigInteger.valueOf(acc));
        int sign = buf[offset + len - 1] & 0x0F;
        boolean negative = switch (sign) {
            case 0x0C, 0x0A, 0x0E, 0x0F -> false;
            case 0x0D, 0x0B -> true;
            default -> throw new CobolDataException(String.format(
                    "invalid packed sign nibble %X in %s", sign, Hex.of(buf, offset, len)));
        };
        return new BigDecimal(negative ? value.negate() : value, scale);
    }

    public static void encode(BigDecimal value, byte[] buf, int offset, int digits, int scale, boolean signed) {
        BigDecimal fitted = Assign.fit(value, digits, scale, signed, false);
        encodeExact(fitted, buf, offset, digits, signed);
    }

    /** Encodes a value that already fits; sign nibble C/D when signed, F when unsigned. */
    static void encodeExact(BigDecimal fitted, byte[] buf, int offset, int digits, boolean signed) {
        int len = byteLength(digits);
        String text = fitted.unscaledValue().abs().toString();
        int nibbles = len * 2 - 1;
        int pad = nibbles - text.length();
        int signNibble = !signed ? 0x0F : (fitted.signum() < 0 ? 0x0D : 0x0C);
        for (int i = 0; i < len; i++) {
            int n1 = nibbleAt(text, 2 * i - pad);
            int n2 = (i == len - 1) ? signNibble : nibbleAt(text, 2 * i + 1 - pad);
            buf[offset + i] = (byte) ((n1 << 4) | n2);
        }
    }

    public static boolean isValid(byte[] buf, int offset, int digits) {
        try {
            decode(buf, offset, digits, 0);
            return true;
        } catch (CobolDataException e) {
            return false;
        }
    }

    private static int nibbleAt(String digits, int index) {
        return index < 0 ? 0 : digits.charAt(index) - '0';
    }

    private static void checkDigit(int nibble, byte[] buf, int offset, int len) {
        if (nibble > 9) {
            throw new CobolDataException(String.format(
                    "invalid packed digit nibble %X in %s", nibble, Hex.of(buf, offset, len)));
        }
    }
}
