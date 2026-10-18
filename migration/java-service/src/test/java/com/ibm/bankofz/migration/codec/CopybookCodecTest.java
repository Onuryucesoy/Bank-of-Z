package com.ibm.bankofz.migration.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CopybookCodecTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private final CopybookCodec codec = new CopybookCodec();

    private static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    @Test
    void alphanumericUsesIbm037AndPadsWithEbcdicSpaces() {
        byte[] rec = new byte[6];
        codec.encodeAlphanumeric("Mr", rec, 0, 6);
        assertEquals("D4 99 40 40 40 40".replace(" ", ""), HEX.formatHex(rec));
        assertEquals("Mr    ", codec.decodeAlphanumeric(rec, 0, 6));
    }

    @Test
    void alphanumericRoundTripsCp037SpecialCharacters() {
        String value = "¢£¬|!";
        byte[] rec = new byte[value.length()];
        codec.encodeAlphanumeric(value, rec, 0, rec.length);
        // CP037-specific code points: these differ from IBM-1047 (¬ is 0x5F in 037, 0xB0 in 1047)
        assertEquals("4AB15F4F5A", HEX.formatHex(rec));
        assertEquals(value, codec.decodeAlphanumeric(rec, 0, rec.length));
    }

    @Test
    void alphanumericRejectsTooLongAndUnmappableValues() {
        assertThrows(CodecException.class, () -> codec.encodeAlphanumeric("TOO LONG", new byte[4], 0, 4));
        assertThrows(CodecException.class, () -> codec.encodeAlphanumeric("€", new byte[4], 0, 4));
    }

    @ParameterizedTest(name = "S9(10)V99 {0} <-> {1}")
    @CsvSource({
        "1234.56,      F0F0F0F0F0F0F1F2F3F4F5C6",
        "-50.00,       F0F0F0F0F0F0F0F0F5F0F0D0",
        "0.00,         F0F0F0F0F0F0F0F0F0F0F0C0",
        "9999999999.99, F9F9F9F9F9F9F9F9F9F9F9C9"
    })
    void signedZonedDecimalRoundTrips(String value, String hex) {
        byte[] rec = new byte[12];
        codec.encodeZoned(new BigDecimal(value), rec, 0, 12, 2, true);
        assertEquals(hex, HEX.formatHex(rec));
        assertEquals(new BigDecimal(value).setScale(2), codec.decodeZoned(rec, 0, 12, 2, true));
    }

    @Test
    void unsignedZonedUsesFZoneAndRejectsNegative() {
        byte[] rec = new byte[6];
        codec.encodeZoned(new BigDecimal("987654"), rec, 0, 6, 0, false);
        assertEquals("F9F8F7F6F5F4", HEX.formatHex(rec));
        assertThrows(CodecException.class, () -> codec.encodeZoned(new BigDecimal("-1"), rec, 0, 6, 0, false));
    }

    @Test
    void zonedDecodeRejectsSpacesAndBadZones() {
        assertThrows(CodecException.class, () -> codec.decodeZoned(hex("40404040"), 0, 4, 0, false));
        assertThrows(CodecException.class, () -> codec.decodeZoned(hex("F1D2F3"), 0, 3, 0, true));
    }

    @ParameterizedTest(name = "S9(13)V9(2) COMP-3 {0} <-> {1}")
    @CsvSource({
        "-1234.56,           000000000123456D",
        "1234.56,            000000000123456C",
        "9999999999999.99,   999999999999999C",
        "0.00,               000000000000000C"
    })
    void packedDecimalMatchesIbacsumBalanceLayout(String value, String hex) {
        byte[] rec = new byte[CopybookCodec.packedSize(15)];
        assertEquals(8, rec.length);
        codec.encodePacked(new BigDecimal(value), rec, 0, 15, 2, true);
        assertEquals(hex, HEX.formatHex(rec));
        assertEquals(new BigDecimal(value).setScale(2), codec.decodePacked(rec, 0, 15, 2));
    }

    @Test
    void packedEvenDigitCountHasLeadingZeroNibbleAndUnsignedUsesF() {
        byte[] rec = new byte[3];
        codec.encodePacked(new BigDecimal("1234"), rec, 0, 4, 0, false);
        assertEquals("01234F", HEX.formatHex(rec));
        assertEquals(new BigDecimal("1234"), codec.decodePacked(rec, 0, 4, 0));
    }

    @Test
    void packedDecodeAcceptsPreferredAndAlternateSigns() {
        assertEquals(new BigDecimal("-12.3"), codec.decodePacked(hex("123B"), 0, 3, 1));
        assertEquals(new BigDecimal("12.3"), codec.decodePacked(hex("123F"), 0, 3, 1));
        assertThrows(CodecException.class, () -> codec.decodePacked(hex("1235"), 0, 3, 1));
        assertThrows(CodecException.class, () -> codec.decodePacked(hex("1A3C"), 0, 3, 1));
    }

    @Test
    void encodingNeverRoundsOrTruncates() {
        byte[] rec = new byte[8];
        assertThrows(CodecException.class, () -> codec.encodePacked(new BigDecimal("1.005"), rec, 0, 15, 2, true));
        assertThrows(CodecException.class, () -> codec.encodePacked(new BigDecimal("10000000000000.00"), rec, 0, 15, 2, true));
        assertThrows(CodecException.class, () -> codec.encodeZoned(new BigDecimal("0.001"), new byte[12], 0, 12, 2, true));
    }

    @Test
    void decimalArithmeticStaysExact() {
        byte[] rec = new byte[8];
        BigDecimal sum = new BigDecimal("0.10").add(new BigDecimal("0.20"));
        codec.encodePacked(sum, rec, 0, 15, 2, true);
        assertEquals(new BigDecimal("0.30"), codec.decodePacked(rec, 0, 15, 2));
    }

    @Test
    void binaryCompAndComp5RoundTrip() {
        byte[] rec = new byte[4];
        codec.encodeBinary(new BigDecimal("20"), rec, 0, 4, 0, true);
        assertEquals("00000014", HEX.formatHex(rec));
        codec.encodeBinary(new BigDecimal("-2"), rec, 0, 4, 0, true);
        assertEquals("FFFFFFFE", HEX.formatHex(rec));
        assertEquals(new BigDecimal("-2"), codec.decodeBinary(rec, 0, 4, 0, true));

        byte[] accid = new byte[CopybookCodec.binarySize(18)];
        codec.encodeBinary(new BigDecimal("1504"), accid, 0, 8, 0, true);
        assertEquals(new BigDecimal("1504"), codec.decodeBinary(accid, 0, 8, 0, true));
        assertThrows(CodecException.class, () -> codec.encodeBinary(new BigDecimal("70000"), new byte[2], 0, 2, 0, false));
    }

    @Test
    void rawDateDigitsStayStringsAndKeepLeadingZeros() {
        byte[] rec = new byte[8];
        codec.encodeRawDigits("01022026", rec, 0, 8);
        assertEquals("F0F1F0F2F2F0F2F6", HEX.formatHex(rec));
        assertEquals("01022026", codec.decodeRawDigits(rec, 0, 8));
        assertThrows(CodecException.class, () -> codec.encodeRawDigits("2026-02-01", rec, 0, 8));
    }

    @Test
    void recordRoundTripOverInqcustPrefix() {
        // First fields of INQCUSTZ (src/base/cics/copy/INQCUSTZ.cpy): EYE X(4), SCODE X(6), CUSTNO 9(10), TITLE X(10)
        RecordLayout layout = new RecordLayout("INQCUSTZ-prefix", 30, List.of(
                FieldSpec.alphanumeric("INQCUST-EYE", 0, 4),
                FieldSpec.alphanumeric("INQCUST-SCODE", 4, 6),
                FieldSpec.zoned("INQCUST-CUSTNO", 10, 10, 0, false),
                FieldSpec.alphanumeric("INQCUST-TITLE", 20, 10)));
        Map<String, Object> values = Map.of("INQCUST-EYE", "CUST", "INQCUST-SCODE", "987654",
                "INQCUST-CUSTNO", new BigDecimal("1"), "INQCUST-TITLE", "Mr");
        byte[] rec = codec.encode(values, layout);
        assertEquals(30, rec.length);
        Map<String, Object> decoded = codec.decode(rec, layout);
        assertEquals("CUST", decoded.get("INQCUST-EYE"));
        assertEquals(new BigDecimal("1"), decoded.get("INQCUST-CUSTNO"));
        assertEquals("Mr        ", decoded.get("INQCUST-TITLE"));
        assertArrayEquals(rec, codec.encode(decoded, layout));
    }

    @Test
    void recordEncodeRejectsFloatingPointValues() {
        RecordLayout layout = new RecordLayout("r", 8, List.of(FieldSpec.packed("BAL", 0, 15, 2, true)));
        assertThrows(CodecException.class, () -> codec.encode(Map.of("BAL", 12.34d), layout));
        assertThrows(IllegalArgumentException.class,
                () -> new RecordLayout("r", 4, List.of(FieldSpec.packed("BAL", 0, 15, 2, true))));
    }

    @Test
    void publicApiNeverExposesDoubleOrFloat() {
        for (Method m : CopybookCodec.class.getMethods()) {
            assertFalse(isFloating(m.getReturnType()), m + " returns floating point");
            for (Parameter p : m.getParameters()) {
                assertFalse(isFloating(p.getType()), m + " takes floating point");
            }
        }
    }

    private static boolean isFloating(Class<?> c) {
        return c == double.class || c == float.class || c == Double.class || c == Float.class;
    }
}
