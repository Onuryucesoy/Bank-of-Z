package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * COBOL receiving-field rules for numeric MOVE / COMPUTE without ON SIZE ERROR (TRUNC(STD)).
 *
 * <ul>
 *   <li>Excess decimal places are truncated toward zero unless ROUNDED is specified
 *       (ROUNDED = round half away from zero, i.e. {@link RoundingMode#HALF_UP}).</li>
 *   <li>Excess high-order integer digits are silently dropped (value modulo 10^digits).</li>
 *   <li>An unsigned receiver stores the absolute value.</li>
 *   <li>A zero result is always positive.</li>
 * </ul>
 * PL/I FIXED DECIMAL assignment with SIZE disabled (the default) truncates the same way.
 */
public final class Assign {
    private Assign() {
    }

    public static BigDecimal fit(BigDecimal value, int digits, int scale, boolean signed, boolean rounded) {
        BigDecimal scaled = value.setScale(scale, rounded ? RoundingMode.HALF_UP : RoundingMode.DOWN);
        BigInteger unscaled = scaled.unscaledValue();
        BigInteger modulus = BigInteger.TEN.pow(digits);
        BigInteger magnitude = unscaled.abs().mod(modulus);
        if (signed && unscaled.signum() < 0) {
            magnitude = magnitude.negate();
        }
        return new BigDecimal(magnitude, scale);
    }

    /** True when {@link #fit} would lose high-order digits (what ON SIZE ERROR would catch). */
    /** {@code digits} is the total PIC digit count (integer + decimal), as for {@link #fit}. */
    public static boolean overflows(BigDecimal value, int digits, int scale) {
        BigInteger integerPart = value.setScale(scale, RoundingMode.DOWN).unscaledValue().abs();
        return integerPart.compareTo(BigInteger.TEN.pow(digits)) >= 0;
    }
}
