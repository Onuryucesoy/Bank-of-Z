package nazare.jmp.cobol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * USAGE COMP-3 (packed decimal) codec: two digits per byte, sign in the low nibble of the last byte.
 *
 * <p>Writes the preferred signs C (+) and D (-), or F for unsigned pictures. Reads any valid sign
 * nibble (A, C, E, F positive; B, D negative). An invalid digit or sign nibble throws
 * {@link IllegalArgumentException}, the Java counterpart of a S0C7 data exception. For pictures
 * with an even digit count the leading pad nibble is always written as 0 and must read as 0.
 */
public final class PackedDecimal {

    private PackedDecimal() {
    }

    public static byte[] encode(BigDecimal value, PicSpec pic) {
        byte[] out = new byte[pic.packedLength()];
        encode(value, pic, out, 0);
        return out;
    }

    public static void encode(BigDecimal value, PicSpec pic, byte[] target, int offset) {
        if (!pic.accepts(value)) {
            throw new ArithmeticException("Size error: " + value.toPlainString() + " does not fit PIC " + pic + " COMP-3");
        }
        BigInteger unscaled = value.setScale(pic.scale(), RoundingMode.UNNECESSARY).unscaledValue();
        String digits = unscaled.abs().toString();
        int nibbleCount = pic.packedLength() * 2;
        int[] nibbles = new int[nibbleCount];
        int pos = nibbleCount - 2;
        for (int i = digits.length() - 1; i >= 0; i--) {
            nibbles[pos--] = digits.charAt(i) - '0';
        }
        nibbles[nibbleCount - 1] = !pic.signed() ? 0xF : (unscaled.signum() < 0 ? 0xD : 0xC);
        for (int i = 0; i < pic.packedLength(); i++) {
            target[offset + i] = (byte) ((nibbles[2 * i] << 4) | nibbles[2 * i + 1]);
        }
    }

    public static BigDecimal decode(byte[] source, PicSpec pic) {
        if (source.length != pic.packedLength()) {
            throw new IllegalArgumentException("PIC " + pic + " COMP-3 is " + pic.packedLength() + " bytes, got " + source.length);
        }
        return decode(source, 0, pic);
    }

    public static BigDecimal decode(byte[] source, int offset, PicSpec pic) {
        int length = pic.packedLength();
        boolean padNibble = pic.totalDigits() % 2 == 0;
        StringBuilder digits = new StringBuilder(pic.totalDigits());
        for (int i = 0; i < length; i++) {
            int b = source[offset + i] & 0xFF;
            int high = b >>> 4;
            int low = b & 0x0F;
            if (i == 0 && padNibble) {
                if (high != 0) {
                    throw invalid(source, offset, length, "non-zero pad nibble");
                }
            } else {
                digits.append(digit(high, source, offset, length));
            }
            if (i < length - 1) {
                digits.append(digit(low, source, offset, length));
            } else {
                boolean negative = switch (low) {
                    case 0xA, 0xC, 0xE, 0xF -> false;
                    case 0xB, 0xD -> true;
                    default -> throw invalid(source, offset, length, "invalid sign nibble");
                };
                BigInteger unscaled = new BigInteger(digits.toString());
                return new BigDecimal(negative ? unscaled.negate() : unscaled, pic.scale());
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static char digit(int nibble, byte[] source, int offset, int length) {
        if (nibble > 9) {
            throw invalid(source, offset, length, "invalid digit nibble");
        }
        return (char) ('0' + nibble);
    }

    private static IllegalArgumentException invalid(byte[] source, int offset, int length, String reason) {
        StringBuilder hex = new StringBuilder();
        for (int i = offset; i < offset + length; i++) {
            hex.append(String.format("%02X", source[i] & 0xFF));
        }
        return new IllegalArgumentException("Invalid packed decimal X'" + hex + "': " + reason);
    }
}
