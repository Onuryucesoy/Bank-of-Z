package com.ibm.bankofz.core.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** Shared PIC 9 range checks. Values never silently lose digits. */
final class Numeric {

    private Numeric() {
    }

    static BigInteger unscaled(BigDecimal value, int digits, int scale, boolean signed) {
        if (value == null) {
            throw new CodecException("numeric value must not be null");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new CodecException(value + " has more than " + scale + " decimal places", e);
        }
        BigInteger unscaled = scaled.unscaledValue();
        if (!signed && unscaled.signum() < 0) {
            throw new CodecException(value + " is negative but the PIC is unsigned");
        }
        if (unscaled.abs().toString().length() > digits) {
            throw new CodecException(value + " does not fit in " + digits + " digits (scale " + scale + ")");
        }
        return unscaled;
    }

    static String leftPad(String s, int width) {
        return "0".repeat(Math.max(0, width - s.length())) + s;
    }
}
