package com.bankofz.migration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CobolDatesTest {

    private static final LocalDate JULY_23_2003 = LocalDate.of(2003, 7, 23);

    @Test
    void db2IntegerIsYearMonthDay() {
        assertEquals(JULY_23_2003, CobolDates.fromDb2Integer(20030723));
        assertEquals(20030723, CobolDates.toDb2Integer(JULY_23_2003));
    }

    @Test
    void recordGroupIsDayMonthYear() {
        assertEquals(JULY_23_2003, CobolDates.fromDdMmYyyy("23072003"));
        assertEquals(JULY_23_2003, CobolDates.fromDdMmYyyy(23072003));
        assertEquals("23072003", CobolDates.toDdMmYyyy(JULY_23_2003));
        assertEquals("01012000", CobolDates.toDdMmYyyy(LocalDate.of(2000, 1, 1)));
        assertEquals(LocalDate.of(2000, 1, 1), CobolDates.fromDdMmYyyy(1012000)); // leading zero lost in 9(8)
    }

    @Test
    void theTwoIntegerFormsAreNotInterchangeable() {
        // 23072003 read as YYYYMMDD would be year 2307, month 20 -> invalid.
        assertThrows(DateTimeException.class, () -> CobolDates.fromDb2Integer(23072003));
    }

    @Test
    void zeroIsNoDate() {
        assertNull(CobolDates.fromDb2Integer(0));
        assertNull(CobolDates.fromDdMmYyyy("00000000"));
        assertNull(CobolDates.fromDdMmYyyy("        "));
        assertEquals(0, CobolDates.toDb2Integer(null));
        assertEquals("00000000", CobolDates.toDdMmYyyy(null));
    }

    @Test
    void invalidCalendarDatesAreRejectedNotAdjusted() {
        assertThrows(DateTimeException.class, () -> CobolDates.fromDdMmYyyy("31022020"));
        assertThrows(DateTimeException.class, () -> CobolDates.fromDb2Integer(20201301));
        assertThrows(IllegalArgumentException.class, () -> CobolDates.fromDdMmYyyy("2307203"));
        assertThrows(IllegalArgumentException.class, () -> CobolDates.toDdMmYyyy(LocalDate.of(10000, 1, 1)));
    }

    @Test
    void db2DateHostVariable() {
        assertEquals(JULY_23_2003, CobolDates.fromDottedDdMmYyyy("23.07.2003"));
        assertThrows(IllegalArgumentException.class, () -> CobolDates.fromDottedDdMmYyyy("2003-07-23"));
    }

    @Test
    void imsTimestampWithShortFraction() {
        // Format used by src/base/ims/LoadData/HISTORY.data.
        assertEquals(LocalDateTime.of(2003, 7, 23, 17, 56, 0, 640_000_000),
                CobolDates.fromImsTimestamp("2003-07-23 17:56:00.64"));
        assertEquals(LocalDateTime.of(2003, 7, 23, 17, 56, 0),
                CobolDates.fromImsTimestamp("2003-07-23 17:56:00"));
    }
}
