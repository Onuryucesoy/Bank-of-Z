package com.ibm.bankofz.bridge.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZonedAndBinaryTest {

    private static String zoned(String v, int digits, int scale, ZonedDecimal.Sign sign) {
        byte[] b = new byte[ZonedDecimal.byteLength(digits, sign)];
        ZonedDecimal.encode(new BigDecimal(v), b, 0, digits, scale, sign);
        return Hex.of(b);
    }

    @ParameterizedTest(name = "{0} -> {3} ({2})")
    @CsvSource({
        "123.45,   5, TRAILING,          F1F2F3F4C5",
        "-123.45,  5, TRAILING,          F1F2F3F4D5",
        "123.45,   5, NONE,              F1F2F3F4F5",
        "-123.45,  5, NONE,              F1F2F3F4F5",
        "-123.45,  5, LEADING,           D1F2F3F4F5",
        "-123.45,  5, LEADING_SEPARATE,  60F1F2F3F4F5",
        "123.45,   5, LEADING_SEPARATE,  4EF1F2F3F4F5",
        "-123.45,  5, TRAILING_SEPARATE, F1F2F3F4F560",
        "-0.001,   5, TRAILING,          F0F0F0F0C0",
    })
    void encodesZoned(String v, int digits, ZonedDecimal.Sign sign, String hex) {
        assertEquals(hex, zoned(v, digits, 2, sign));
        if (sign != ZonedDecimal.Sign.NONE) {
            BigDecimal expected = new BigDecimal(v).setScale(2, java.math.RoundingMode.DOWN);
            assertEquals(0, expected.compareTo(ZonedDecimal.decode(Hex.parse(hex), 0, digits, 2, sign)));
        }
    }

    @Test
    void zonedAcceptsAlternateSignZonesAndRejectsSpaces() {
        assertEquals(new BigDecimal("-1.23"), ZonedDecimal.decode(Hex.parse("F1F2B3"), 0, 3, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals(new BigDecimal("1.23"), ZonedDecimal.decode(Hex.parse("F1F2F3"), 0, 3, 2, ZonedDecimal.Sign.TRAILING));
        // MOVE SPACES TO a numeric field followed by arithmetic is an S0C7 on z/OS.
        assertThrows(CobolDataException.class,
                () -> ZonedDecimal.decode(Hex.parse("404040"), 0, 3, 0, ZonedDecimal.Sign.NONE));
        assertFalse(ZonedDecimal.isValid(Hex.parse("F1C2F3"), 0, 3, ZonedDecimal.Sign.NONE));
        assertTrue(ZonedDecimal.isValid(Hex.parse("F1F2C3"), 0, 3, ZonedDecimal.Sign.TRAILING));
    }

    @Test
    void compTruncatesToPictureDigitsUnderTruncStd() {
        byte[] b = new byte[2];
        // PIC S9(4) COMP: 12345 -> 2345 (decimal truncation), not binary wrap.
        Binary.encode(new BigDecimal(12345), b, 0, 4, 0, true, false);
        assertEquals("0929", Hex.of(b));
        assertEquals(new BigDecimal(2345), Binary.decode(b, 0, 4, 0, true));
        Binary.encode(new BigDecimal(-1), b, 0, 4, 0, true, false);
        assertEquals("FFFF", Hex.of(b));
    }

    @Test
    void comp5KeepsFullBinaryRange() {
        byte[] b = new byte[2];
        // PIC S9(4) COMP-5 holds up to 32767.
        Binary.encode(new BigDecimal(12345), b, 0, 4, 0, true, true);
        assertEquals("3039", Hex.of(b));
        assertEquals(new BigDecimal(12345), Binary.decode(b, 0, 4, 0, true));
        byte[] l = new byte[8];
        Binary.encode(new BigDecimal(Long.MIN_VALUE), l, 0, 18, 0, true, true);
        assertEquals("8000000000000000", Hex.of(l));
        assertEquals(new BigDecimal(Long.MIN_VALUE), Binary.decode(l, 0, 18, 0, true));
    }

    @Test
    void binarySizes() {
        assertEquals(2, Binary.byteLength(4));
        assertEquals(4, Binary.byteLength(5));
        assertEquals(4, Binary.byteLength(9));
        assertEquals(8, Binary.byteLength(10));
        assertEquals(8, Binary.byteLength(18));
    }

    @ParameterizedTest(name = "{0} as {1} -> [{2}]")
    @CsvSource(delimiter = '|', value = {
        "1234.5     | ZZZZZZZZZZZZZ.99 |          1234.50",
        "0          | ZZZZZZZZZZZZZ.99 |               .00",
        "-1234.5    | ZZZZZZZZZZZZZ.99 |          1234.50",
        "0.07       | ZZZZZZZZZZZZZ.99 |               .07",
        "1234567.891| ZZ,ZZZ,ZZ9.99    |  1,234,567.89",
        "5          | ZZ,ZZZ,ZZ9.99    |          5.00",
        "-42.1      | ---,--9.99       |     -42.10",
        "42.1       | ---,--9.99       |      42.10",
        "-42.1      | ZZZ9.99CR        |   42.10CR",
        "42.1       | ZZZ9.99CR        | '  42.10  '",
        "42.1       | +ZZZ9.99         | +  42.10",
    })
    void formatsNumericEdited(String v, String pic, String expected) {
        assertEquals(expected.length() == pic.length() ? expected : String.format("%" + pic.length() + "s", expected),
                Edited.format(new BigDecimal(v.trim()), pic.trim()));
    }

    @Test
    void textPadsAndTruncatesInEbcdic() {
        byte[] b = new byte[4];
        Text.encode("AB", b, 0, 4, Ebcdic.CP037);
        assertEquals("C1C24040", Hex.of(b));
        Text.encode("ABCDE", b, 0, 4, Ebcdic.CP1047);
        assertEquals("C1C2C3C4", Hex.of(b));
        // The two code pages differ on brackets, which the copybook literals never use, but must not be mixed up.
        assertEquals("BA", Hex.of("[".getBytes(Ebcdic.CP037)));
        assertEquals("AD", Hex.of("[".getBytes(Ebcdic.CP1047)));
    }
}
