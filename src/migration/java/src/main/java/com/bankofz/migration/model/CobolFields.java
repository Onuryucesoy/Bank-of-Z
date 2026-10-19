package com.bankofz.migration.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Validation helpers that enforce a PIC clause on a Java value. Used by the
 * compact constructors of the DTO records so an instance can always be written
 * back into the original COBOL layout without truncation or rounding.
 */
public final class CobolFields {

    private CobolFields() {
    }

    /**
     * {@code PIC X(n)}: trailing blanks are removed (COBOL pads with spaces),
     * {@code null} becomes the empty string, longer values are rejected.
     */
    public static String text(String value, String pic, String field) {
        PicClause p = requireKind(pic, field, true);
        String s = value == null ? "" : value.stripTrailing();
        if (s.length() > p.length()) {
            throw new IllegalArgumentException(field + " length " + s.length() + " exceeds PIC " + pic);
        }
        return s;
    }

    /**
     * Unsigned integer {@code PIC 9(n)} kept as a key string: must be 1..n digits
     * and is left-padded with zeros to exactly n characters, matching the
     * {@code CHAR(n)} Db2 key columns (CUSTOMER_NUMBER, ACCOUNT_NUMBER, ...).
     */
    public static String digits(String value, String pic, String field) {
        PicClause p = requireKind(pic, field, false);
        if (p.signed() || p.scale() != 0) {
            throw new IllegalArgumentException(field + ": PIC " + pic + " is not an unsigned integer");
        }
        Objects.requireNonNull(value, field);
        String s = value.strip();
        if (s.isEmpty() || s.length() > p.length() || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException(field + " '" + value + "' does not fit PIC " + pic);
        }
        return "0".repeat(p.length() - s.length()) + s;
    }

    /**
     * Fixed-point numeric (DISPLAY or COMP-3). The result always has exactly the
     * PIC scale; values with more fractional digits, more integer digits, or a
     * negative sign on an unsigned PIC are rejected instead of being rounded.
     */
    public static BigDecimal decimal(BigDecimal value, String pic, String field) {
        PicClause p = requireKind(pic, field, false);
        Objects.requireNonNull(value, field);
        BigDecimal scaled;
        try {
            scaled = value.setScale(p.scale(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " " + value + " has more than "
                    + p.scale() + " decimal places (PIC " + pic + ")", e);
        }
        checkRange(scaled, p, pic, field);
        return scaled;
    }

    /** Integer numeric (DISPLAY, COMP or COMP-5) range check against the PIC digits. */
    public static long integer(long value, String pic, String field) {
        PicClause p = requireKind(pic, field, false);
        if (p.scale() != 0) {
            throw new IllegalArgumentException(field + ": PIC " + pic + " has a scale");
        }
        checkRange(BigDecimal.valueOf(value), p, pic, field);
        return value;
    }

    private static void checkRange(BigDecimal v, PicClause p, String pic, String field) {
        if (!p.signed() && v.signum() < 0) {
            throw new IllegalArgumentException(field + " " + v + " is negative but PIC " + pic + " is unsigned");
        }
        if (v.abs().compareTo(p.maxValue()) > 0) {
            throw new IllegalArgumentException(field + " " + v + " exceeds PIC " + pic);
        }
    }

    private static PicClause requireKind(String pic, String field, boolean alphanumeric) {
        PicClause p = PicClause.parse(pic);
        if (p.alphanumeric() != alphanumeric) {
            throw new IllegalArgumentException(field + ": PIC " + pic + " is not "
                    + (alphanumeric ? "alphanumeric" : "numeric"));
        }
        return p;
    }
}
