package com.ibm.bankofz.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** PL/I built-ins and conversions BNKSTMT relies on, with PL/I's fixed-length string semantics. */
final class Pli {
    private Pli() {
    }

    /** Assignment to {@code CHAR(n)}: truncate on the right or pad with blanks. */
    static String chr(String s, int n) {
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }

    /** {@code TRIM(s)}: leading and trailing blanks. */
    static String trim(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && s.charAt(b) == ' ') {
            b++;
        }
        while (e > b && s.charAt(e - 1) == ' ') {
            e--;
        }
        return s.substring(b, e);
    }

    /** {@code CENTRE(s, n)}: when the padding is odd the extra blank goes on the right. */
    static String centre(String s, int n) {
        if (s.length() >= n) {
            return s.substring((s.length() - n) / 2, (s.length() - n) / 2 + n);
        }
        int left = (n - s.length()) / 2;
        return " ".repeat(left) + s + " ".repeat(n - s.length() - left);
    }

    /**
     * {@code F(w,d)} edit format: rounded to d places, right-aligned in w. A value that does not fit raises
     * SIZE in PL/I (disabled by default, so the output is undefined); here it is returned unpadded so the
     * parallel-run report diff shows it instead of hiding it.
     */
    static String f(BigDecimal v, int w, int d) {
        String s = v.setScale(d, RoundingMode.HALF_UP).toPlainString();
        if (s.startsWith("-0.") && v.setScale(d, RoundingMode.HALF_UP).signum() == 0) {
            s = s.substring(1);
        }
        return s.length() >= w ? s : " ".repeat(w - s.length()) + s;
    }

    /** {@code F(w)} for integers. */
    static String f(long v, int w) {
        return f(BigDecimal.valueOf(v), w, 0);
    }

    /** {@code TRIM(CHAR(x))} for FIXED DEC(p,q): the digits with one zero before the point. */
    static String charTrim(BigDecimal v, int scale) {
        return v.setScale(scale, RoundingMode.DOWN).toPlainString();
    }
}
