package nazare.jmp.cobol;

import java.time.LocalDate;

/**
 * A date held as separate {@code PIC 9999 / 99 / 99} fields. Values are kept as-is, so an all-zero
 * or otherwise impossible date (which COBOL stores happily) survives a round trip;
 * {@link #toLocalDate()} is where calendar validation happens.
 */
public record CobolDate(int year, int month, int day) {

    public static final CobolDate ZERO = new CobolDate(0, 0, 0);

    public CobolDate {
        check(year, 9999, "year");
        check(month, 99, "month");
        check(day, 99, "day");
    }

    public static CobolDate of(LocalDate date) {
        return new CobolDate(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    /** Throws {@link java.time.DateTimeException} if the fields are not a real calendar date. */
    public LocalDate toLocalDate() {
        return LocalDate.of(year, month, day);
    }

    private static void check(int value, int max, String name) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " " + value + " does not fit its PIC 9 field");
        }
    }
}
