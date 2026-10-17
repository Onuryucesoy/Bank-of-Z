package nazare.jmp.cobol;

import java.math.BigDecimal;

/**
 * Shape of a COBOL numeric PICTURE: {@code [S]9(integerDigits)V9(scale)}.
 * Usage (DISPLAY, COMP-3) is chosen by the codec, not by this type.
 */
public record PicSpec(int integerDigits, int scale, boolean signed) {

    /** {@code PIC S9(10)V99}: CICS COMMAREA and Db2 host-variable amounts. */
    public static final PicSpec S9_10_V99 = new PicSpec(10, 2, true);
    /** {@code PIC S9(13)V9(2)}: IMS BALANCE/AMOUNT, DBD {@code DECIMAL(15,2)}. */
    public static final PicSpec S9_13_V99 = new PicSpec(13, 2, true);
    /** {@code PIC S9(4)V99}: Db2 interest-rate host variable. */
    public static final PicSpec S9_4_V99 = new PicSpec(4, 2, true);
    /** {@code PIC 9(4)V99}: interest rate in the ACCOUNT/INQACC copybooks. */
    public static final PicSpec U9_4_V99 = new PicSpec(4, 2, false);

    public PicSpec {
        if (integerDigits < 0 || scale < 0 || integerDigits + scale < 1 || integerDigits + scale > 31) {
            throw new IllegalArgumentException("Unsupported PICTURE: integerDigits=" + integerDigits + " scale=" + scale);
        }
    }

    public static PicSpec signed(int integerDigits, int scale) {
        return new PicSpec(integerDigits, scale, true);
    }

    public static PicSpec unsigned(int integerDigits, int scale) {
        return new PicSpec(integerDigits, scale, false);
    }

    public int totalDigits() {
        return integerDigits + scale;
    }

    /** Bytes used by this picture as USAGE DISPLAY (zoned decimal). */
    public int zonedLength() {
        return totalDigits();
    }

    /** Bytes used by this picture as USAGE COMP-3 (packed decimal). */
    public int packedLength() {
        return totalDigits() / 2 + 1;
    }

    /** Largest magnitude the picture can hold, e.g. 9999999999.99 for S9(10)V99. */
    public BigDecimal maxMagnitude() {
        return BigDecimal.TEN.pow(integerDigits).subtract(BigDecimal.ONE.movePointLeft(scale)).setScale(scale);
    }

    /** True when {@code value} has at most {@code scale} decimals and fits the integer digits (and sign). */
    public boolean accepts(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > scale) {
            return false;
        }
        if (!signed && value.signum() < 0) {
            return false;
        }
        return value.abs().compareTo(maxMagnitude()) <= 0;
    }

    @Override
    public String toString() {
        return (signed ? "S" : "") + "9(" + integerDigits + ")" + (scale > 0 ? "V9(" + scale + ")" : "");
    }
}
