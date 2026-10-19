package com.bankofz.migration.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * Conversions between the date representations used by Bank of Z and
 * {@link LocalDate}/{@link LocalDateTime}. There are four of them, and mixing
 * them up silently swaps day and year, so every DTO documents which one applies.
 *
 * <ol>
 *   <li><b>Record-layout groups, {@code DDMMYYYY}.</b> CUSTOMER.cpy declares
 *       {@code CUSTOMER-DOB}, {@code CUSTOMER-CREATED-DATE} and
 *       {@code CUSTOMER-CS-REVIEW-DATE} as groups {@code DAY PIC 99},
 *       {@code MONTH PIC 99}, {@code YEAR PIC 9999}. ACCOUNT.cpy declares
 *       {@code ACCOUNT-OPENED}, {@code ACCOUNT-LAST-STMT-DATE} and
 *       {@code ACCOUNT-NEXT-STMT-DATE} as {@code PIC 9(8)} with a group that
 *       {@code REDEFINES} it the same way. Byte order is therefore day, month,
 *       year: 23 July 2003 is {@code "23072003"} and, read as the 9(8) number,
 *       {@code 23072003}. See {@link #fromDdMmYyyy(String)}.</li>
 *   <li><b>Db2 INTEGER, {@code YYYYMMDD}.</b> CUSTDB2.cpy stores the three
 *       customer dates as INTEGER. CRECUST computes
 *       {@code year * 10000 + month * 100 + day} and INQCUST reverses it with
 *       {@code / 10000} and {@code MOD}, so 23 July 2003 is {@code 20030723}.
 *       This is <em>not</em> the 9(8) value of the record group above.
 *       See {@link #fromDb2Integer(int)}.</li>
 *   <li><b>Db2 DATE host variables.</b> ACCDB2.cpy uses DATE; CREACC/UPDACC
 *       move it through {@code PIC X(10)} groups {@code DD.MM.YYYY}
 *       (see {@link #fromDottedDdMmYyyy(String)}); via JDBC it is a plain
 *       {@link LocalDate}.</li>
 *   <li><b>IMS timestamps.</b> 23-byte {@code yyyy-MM-dd HH:mm:ss.SSS} text
 *       with 0-3 fraction digits (HISTORY.TIME, CUSTOMER.LASTLOGIN).</li>
 * </ol>
 *
 * Zero (and all-zero / blank text) is the COBOL "no date" value and maps to
 * {@code null}; invalid calendar dates (e.g. 31 February) throw
 * {@link java.time.DateTimeException} rather than being adjusted.
 */
public final class CobolDates {

    /** {@code yyyy-MM-dd HH:mm:ss[.S..SSS]} as written by the IMS programs and load data. */
    public static final DateTimeFormatter IMS_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 3, true)
            .optionalEnd()
            .toFormatter();

    private CobolDates() {
    }

    /** Db2 INTEGER {@code YYYYMMDD} (CUSTOMER_* date columns) to a date; 0 is {@code null}. */
    public static LocalDate fromDb2Integer(int yyyymmdd) {
        if (yyyymmdd == 0) {
            return null;
        }
        if (yyyymmdd < 0 || yyyymmdd > 99_991_231) {
            throw new IllegalArgumentException("Not a YYYYMMDD integer: " + yyyymmdd);
        }
        return LocalDate.of(yyyymmdd / 10_000, yyyymmdd / 100 % 100, yyyymmdd % 100);
    }

    /** Inverse of {@link #fromDb2Integer(int)}; {@code null} is 0. */
    public static int toDb2Integer(LocalDate date) {
        if (date == null) {
            return 0;
        }
        requireFourDigitYear(date);
        return date.getYear() * 10_000 + date.getMonthValue() * 100 + date.getDayOfMonth();
    }

    /**
     * Record-layout date group / REDEFINES of a {@code PIC 9(8)}: 8 digits in
     * day, month, year order. {@code "00000000"} or blanks are {@code null}.
     */
    public static LocalDate fromDdMmYyyy(String ddmmyyyy) {
        if (ddmmyyyy == null || ddmmyyyy.isBlank() || ddmmyyyy.equals("00000000")) {
            return null;
        }
        if (ddmmyyyy.length() != 8 || !ddmmyyyy.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Not a DDMMYYYY group: '" + ddmmyyyy + "'");
        }
        return LocalDate.of(Integer.parseInt(ddmmyyyy.substring(4, 8)),
                Integer.parseInt(ddmmyyyy.substring(2, 4)),
                Integer.parseInt(ddmmyyyy.substring(0, 2)));
    }

    /** The numeric value of the {@code PIC 9(8)} that the DDMMYYYY group redefines. */
    public static LocalDate fromDdMmYyyy(int ddmmyyyy) {
        if (ddmmyyyy < 0 || ddmmyyyy > 99_999_999) {
            throw new IllegalArgumentException("Not a PIC 9(8) value: " + ddmmyyyy);
        }
        return fromDdMmYyyy(String.format("%08d", ddmmyyyy));
    }

    /** Inverse of {@link #fromDdMmYyyy(String)}; {@code null} is {@code "00000000"}. */
    public static String toDdMmYyyy(LocalDate date) {
        if (date == null) {
            return "00000000";
        }
        requireFourDigitYear(date);
        return String.format("%02d%02d%04d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    /** Db2 DATE host-variable layout {@code DD.MM.YYYY} used by CREACC/UPDACC. */
    public static LocalDate fromDottedDdMmYyyy(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() != 10 || value.charAt(2) != '.' || value.charAt(5) != '.') {
            throw new IllegalArgumentException("Not a DD.MM.YYYY date: '" + value + "'");
        }
        return fromDdMmYyyy(value.substring(0, 2) + value.substring(3, 5) + value.substring(6));
    }

    /** IMS 23-byte timestamp text; blanks are {@code null}. */
    public static LocalDateTime fromImsTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalDateTime.parse(value.strip(), IMS_TIMESTAMP);
    }

    private static void requireFourDigitYear(LocalDate date) {
        if (date.getYear() < 1 || date.getYear() > 9999) {
            throw new IllegalArgumentException("Year does not fit PIC 9999: " + date);
        }
    }
}
