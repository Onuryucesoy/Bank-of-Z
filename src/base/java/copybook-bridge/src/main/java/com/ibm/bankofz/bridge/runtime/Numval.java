package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;

/**
 * {@code FUNCTION NUMVAL}: optional leading/trailing blanks, one sign (leading +/-, trailing +/-/CR/DB),
 * digits with at most one decimal point. All blanks yield 0. Any other argument is undefined in
 * COBOL; here it is rejected so the Java loader stops instead of inserting an unpredictable value.
 */
public final class Numval {
    private Numval() {
    }

    public static BigDecimal of(String arg) {
        String s = arg.strip();
        if (s.isEmpty()) {
            return BigDecimal.ZERO;
        }
        boolean negative = false;
        if (s.endsWith("CR") || s.endsWith("DB")) {
            negative = true;
            s = s.substring(0, s.length() - 2).strip();
        } else if (s.endsWith("-") || s.endsWith("+")) {
            negative = s.endsWith("-");
            s = s.substring(0, s.length() - 1).strip();
        } else if (s.startsWith("-") || s.startsWith("+")) {
            negative = s.startsWith("-");
            s = s.substring(1).strip();
        }
        if (!s.matches("\\d*\\.?\\d*") || s.equals(".") || s.isEmpty()) {
            throw new NumberFormatException("NUMVAL argument not numeric: '" + arg + "'");
        }
        BigDecimal v = new BigDecimal(s.startsWith(".") ? "0" + s : s.endsWith(".") ? s + "0" : s);
        return negative ? v.negate() : v;
    }
}
