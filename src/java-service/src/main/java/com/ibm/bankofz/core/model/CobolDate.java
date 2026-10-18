package com.ibm.bankofz.core.model;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * A day/month/year triple exactly as COBOL stores it. Kept separate from
 * {@link LocalDate} because the copybooks legitimately carry values that are
 * not calendar dates (all zeros after INITIALIZE, CRECUST's year < 1601
 * rejection path), and golden-master comparison must preserve them.
 */
public record CobolDate(int day, int month, int year) {

    public static final CobolDate ZERO = new CobolDate(0, 0, 0);

    public CobolDate {
        if (day < 0 || day > 99 || month < 0 || month > 99 || year < 0 || year > 9999) {
            throw new IllegalArgumentException("date parts out of PIC range: " + day + "/" + month + "/" + year);
        }
    }

    /** PIC 9(8) laid out DDMMYYYY (ACCOUNT-OPENED, INQACC-OPENED ...). */
    public static CobolDate ofDdMmYyyy(long n) {
        return new CobolDate((int) (n / 1_000_000), (int) (n / 10_000 % 100), (int) (n % 10_000));
    }

    public long toDdMmYyyy() {
        return day * 1_000_000L + month * 10_000L + year;
    }

    /** PIC 9(8) / Db2 INTEGER laid out YYYYMMDD (PROC-TRAN-DATE, CUSTOMER_DATE_OF_BIRTH ...). */
    public static CobolDate ofYyyyMmDd(long n) {
        return new CobolDate((int) (n % 100), (int) (n / 100 % 100), (int) (n / 10_000));
    }

    public long toYyyyMmDd() {
        return year * 10_000L + month * 100L + day;
    }

    public static CobolDate of(LocalDate d) {
        return d == null ? ZERO : new CobolDate(d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }

    public boolean isZero() {
        return day == 0 && month == 0 && year == 0;
    }

    public Optional<LocalDate> toLocalDate() {
        try {
            return isZero() ? Optional.empty() : Optional.of(LocalDate.of(year, month, day));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }

    /** {@code DD.MM.YYYY}, the Db2 EUR DATE string used by the CICS host variables. */
    public String toEurString() {
        return String.format("%02d.%02d.%04d", day, month, year);
    }

    public static CobolDate ofEurString(String s) {
        if (s == null || s.isBlank()) {
            return ZERO;
        }
        String[] p = s.trim().split("[.\\-/]");
        if (p.length != 3) {
            throw new IllegalArgumentException("not a DD.MM.YYYY date: " + s);
        }
        return new CobolDate(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
