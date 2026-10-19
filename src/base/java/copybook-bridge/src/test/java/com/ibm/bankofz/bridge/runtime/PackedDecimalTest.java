package com.ibm.bankofz.bridge.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMP-3 equivalence. Each row is what Enterprise COBOL (TRUNC(STD), no ON SIZE ERROR) stores for
 * {@code MOVE source TO target} / {@code COMPUTE target ROUNDED = source}. A failure here is a phase
 * blocker: the Java path would persist different packed bytes than the COBOL path.
 */
class PackedDecimalTest {

    private static byte[] encode(String value, int digits, int scale, boolean signed) {
        byte[] b = new byte[PackedDecimal.byteLength(digits)];
        PackedDecimal.encode(new BigDecimal(value), b, 0, digits, scale, signed);
        return b;
    }

    @ParameterizedTest(name = "MOVE {0} TO PIC {1}{2} COMP-3 -> {3}")
    @CsvSource({
        // value, S?, digits/scale picture, expected hex
        "123.45,      S, 3/2, 12345C",
        "-123.45,     S, 3/2, 12345D",
        "123.45,      U, 3/2, 12345F",
        "0,           S, 3/2, 00000C",
        "-0.00,       S, 3/2, 00000C",
        "1,           S, 13/2, 000000000000100C",
        "-1,          S, 13/2, 000000000000100D",
        "9999999999999.99, S, 13/2, 999999999999999C",
        "-9999999999999.99, S, 13/2, 999999999999999D",
        "12,          S, 4/0, 00012C",
        "12,          S, 3/0, 012C",
        "999999999999999999, S, 18/0, 0999999999999999999C",
        "1234567890123456789012345678901, S, 31/0, 1234567890123456789012345678901C",
    })
    void encodesExactBytes(String value, String sign, String pic, String hex) {
        String[] p = pic.split("/");
        int scale = Integer.parseInt(p[1]);
        int digits = Integer.parseInt(p[0]) + scale;
        assertEquals(hex, Hex.of(encode(value, digits, scale, sign.equals("S"))));
    }

    @ParameterizedTest(name = "MOVE {0} TO S9(3)V99 COMP-3 = {1}; ROUNDED = {2}")
    @CsvSource({
        "123.456,   123.45,  123.46",
        "123.454,   123.45,  123.45",
        "123.455,   123.45,  123.46",
        "-123.455, -123.45, -123.46",
        "-123.454, -123.45, -123.45",
        "0.005,     0.00,    0.01",
        "-0.005,    0.00,   -0.01",
        "-0.004,    0.00,    0.00",
        // high-order truncation: integer digits beyond PIC are dropped, not rounded or saturated
        "12345.67,  345.67,  345.67",
        "-12345.67, -345.67, -345.67",
        "999.995,   999.99,  0.00",
        "1000,      0.00,    0.00",
    })
    void truncatesAndRoundsLikeCobol(String source, String truncated, String rounded) {
        BigDecimal v = new BigDecimal(source);
        assertEquals(new BigDecimal(truncated), Assign.fit(v, 5, 2, true, false));
        assertEquals(new BigDecimal(rounded), Assign.fit(v, 5, 2, true, true));
        byte[] b = encode(source, 5, 2, true);
        assertEquals(new BigDecimal(truncated), PackedDecimal.decode(b, 0, 5, 2));
    }

    @Test
    void negativeZeroIsStoredPositive() {
        // -0.004 truncates to zero; COBOL stores the preferred positive sign, never X'...0D'.
        assertEquals("00000C", Hex.of(encode("-0.004", 5, 2, true)));
    }

    @Test
    void unsignedReceiverDropsTheSign() {
        assertEquals("12345F", Hex.of(encode("-123.45", 5, 2, false)));
        assertEquals(new BigDecimal("123.45"), PackedDecimal.decode(Hex.parse("12345F"), 0, 5, 2));
    }

    @ParameterizedTest(name = "sign nibble {0} reads as {1}")
    @CsvSource({"C, 123.45", "F, 123.45", "A, 123.45", "E, 123.45", "D, -123.45", "B, -123.45"})
    void acceptsEveryValidSignNibble(String nibble, String expected) {
        assertEquals(new BigDecimal(expected), PackedDecimal.decode(Hex.parse("12345" + nibble), 0, 5, 2));
    }

    @Test
    void invalidNibblesAreDataExceptions() {
        assertThrows(CobolDataException.class, () -> PackedDecimal.decode(Hex.parse("123451"), 0, 5, 2));
        assertThrows(CobolDataException.class, () -> PackedDecimal.decode(Hex.parse("1A345C"), 0, 5, 2));
        assertThrows(CobolDataException.class, () -> PackedDecimal.decode(Hex.parse("404040"), 0, 5, 2));
        assertFalse(PackedDecimal.isValid(Hex.parse("000000"), 0, 5));
        assertTrue(PackedDecimal.isValid(Hex.parse("00000C"), 0, 5));
    }

    @Test
    void evenDigitCountLeavesHighNibbleZero() {
        // PIC S9(4) COMP-3 occupies 3 bytes; the unused high-order nibble must be 0.
        assertArrayEquals(Hex.parse("01234C"), encode("1234", 4, 0, true));
        assertEquals(new BigDecimal("1234"), PackedDecimal.decode(Hex.parse("01234C"), 0, 4, 0));
    }

    /** IMS DBD ACCOUNT/HISTORY declare DECIMAL(15,2) over 8 bytes == COBOL S9(13)V9(2) COMP-3 == PL/I FIXED DEC(15,2). */
    @Test
    void imsDecimal15x2MatchesCobolS9of13V99() {
        assertEquals(8, PackedDecimal.byteLength(15));
        byte[] b = encode("-1234567.89", 15, 2, true);
        assertEquals("000000123456789D", Hex.of(b)); // pragma: allowlist secret
    }

    /**
     * Db2 ACCOUNT balances are DECIMAL(12,2) (CICS S9(10)V99) while IMS uses DECIMAL(15,2). A value that fits IMS
     * but not Db2 is silently truncated by COBOL MOVE; the harness must flag it rather than let Java "fix" it.
     */
    @Test
    void detectsDb2NarrowingOverflow() {
        BigDecimal imsBalance = new BigDecimal("12345678901.23");
        assertTrue(Assign.overflows(imsBalance, 12, 2));
        assertEquals(new BigDecimal("2345678901.23"), Assign.fit(imsBalance, 12, 2, true, false));
        assertFalse(Assign.overflows(new BigDecimal("9999999999.99"), 12, 2));
    }

    @Test
    void roundTripsEveryValueOfASmallField() {
        byte[] b = new byte[3];
        for (int i = -99999; i <= 99999; i++) {
            BigDecimal v = BigDecimal.valueOf(i, 2);
            PackedDecimal.encode(v, b, 0, 5, 2, true);
            assertEquals(v, PackedDecimal.decode(b, 0, 5, 2));
        }
    }
}
