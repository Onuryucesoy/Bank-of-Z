package nazare.jmp.cobol;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * USAGE DISPLAY numeric (EBCDIC zoned decimal) codec: one digit per byte as X'Fd', with the sign
 * carried in the zone of the last byte (trailing overpunch, the COBOL default without SIGN SEPARATE).
 *
 * <p>Writes zone C (+) / D (-) for signed pictures and F for unsigned ones. Reads zone F, C, A or E
 * as positive and D or B as negative in the last byte, and requires zone F elsewhere. Anything else
 * (spaces, LOW-VALUES) throws {@link IllegalArgumentException}.
 */
public final class ZonedDecimal {

    private ZonedDecimal() {
    }

    public static byte[] encode(BigDecimal value, PicSpec pic) {
        byte[] out = new byte[pic.zonedLength()];
        encode(value, pic, out, 0);
        return out;
    }

    public static void encode(BigDecimal value, PicSpec pic, byte[] target, int offset) {
        if (!pic.accepts(value)) {
            throw new ArithmeticException("Size error: " + value.toPlainString() + " does not fit PIC " + pic);
        }
        BigInteger unscaled = value.setScale(pic.scale(), RoundingMode.UNNECESSARY).unscaledValue();
        String digits = unscaled.abs().toString();
        int length = pic.zonedLength();
        int pad = length - digits.length();
        for (int i = 0; i < length; i++) {
            int d = i < pad ? 0 : digits.charAt(i - pad) - '0';
            target[offset + i] = (byte) (0xF0 | d);
        }
        if (pic.signed()) {
            int zone = unscaled.signum() < 0 ? 0xD0 : 0xC0;
            target[offset + length - 1] = (byte) (zone | (target[offset + length - 1] & 0x0F));
        }
    }

    /** Convenience for unsigned integer fields such as {@code PIC 9(8)}. */
    public static void encodeUnsigned(long value, int digits, byte[] target, int offset) {
        encode(BigDecimal.valueOf(value), PicSpec.unsigned(digits, 0), target, offset);
    }

    public static BigDecimal decode(byte[] source, int offset, PicSpec pic) {
        int length = pic.zonedLength();
        StringBuilder digits = new StringBuilder(length);
        boolean negative = false;
        for (int i = 0; i < length; i++) {
            int b = source[offset + i] & 0xFF;
            int zone = b >>> 4;
            int d = b & 0x0F;
            boolean last = i == length - 1;
            boolean zoneOk = zone == 0xF || (last && (zone == 0xC || zone == 0xA || zone == 0xE || zone == 0xD || zone == 0xB));
            if (!zoneOk || d > 9) {
                throw new IllegalArgumentException(String.format(
                        "Invalid zoned decimal byte X'%02X' at position %d of PIC %s", b, i, pic));
            }
            if (last && (zone == 0xD || zone == 0xB)) {
                negative = true;
            }
            digits.append((char) ('0' + d));
        }
        BigInteger unscaled = new BigInteger(digits.toString());
        return new BigDecimal(negative ? unscaled.negate() : unscaled, pic.scale());
    }

    public static long decodeUnsigned(byte[] source, int offset, int digits) {
        return decode(source, offset, PicSpec.unsigned(digits, 0)).longValueExact();
    }
}
