package com.ibm.bankofz.core.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CodecTest {

    private static byte[] hex(String h) {
        return HexFormat.of().parseHex(h);
    }

    @ParameterizedTest
    @CsvSource({
        // value, digits, scale, signed, expected hex  (IBM Enterprise COBOL encodings)
        "1234.56, 12, 2, true, 0000000123456C",
        "-1234.56, 12, 2, true, 0000000123456D",
        "0, 12, 2, true, 0000000000000C",
        "123, 3, 0, false, 123F",
        "99999999.99, 10, 2, true, 09999999999C",
        "-0.01, 6, 2, true, 0000001D",
    })
    void packedDecimalMatchesComp3Layout(String value, int digits, int scale, boolean signed, String hex) {
        String padded = hex.length() % 2 == 0 ? hex : "0" + hex;
        byte[] expected = hex(padded);
        byte[] actual = PackedDecimal.encode(new BigDecimal(value), digits, scale, signed);
        assertArrayEquals(expected, actual);
        assertEquals(0, new BigDecimal(value).compareTo(PackedDecimal.decode(actual, 0, digits, scale)));
    }

    @Test
    void packedDecimalAcceptsPreferredAndAlternateSigns() {
        assertEquals(new BigDecimal("12.3"), PackedDecimal.decode(hex("123F"), 0, 3, 1));
        assertEquals(new BigDecimal("-12.3"), PackedDecimal.decode(hex("123B"), 0, 3, 1));
        assertThrows(CodecException.class, () -> PackedDecimal.decode(hex("1239"), 0, 3, 1));
        assertThrows(CodecException.class, () -> PackedDecimal.decode(hex("1A3C"), 0, 3, 1));
    }

    @Test
    void zonedDecimalUsesSignOverpunch() {
        assertArrayEquals(hex("F0F0F1F2F3C4"), ZonedDecimal.encode(new BigDecimal("12.34"), 6, 2, true));
        assertArrayEquals(hex("F0F0F1F2F3D4"), ZonedDecimal.encode(new BigDecimal("-12.34"), 6, 2, true));
        assertArrayEquals(hex("F0F0F1F2F3F4"), ZonedDecimal.encode(new BigDecimal("12.34"), 6, 2, false));
        assertEquals(new BigDecimal("-12.34"), ZonedDecimal.decode(hex("F0F0F1F2F3D4"), 0, 6, 2));
        assertEquals(new BigDecimal("12.34"), ZonedDecimal.decode(hex("F0F0F1F2F3F4"), 0, 6, 2));
        // spaces in a numeric field are a data error, not zero
        assertThrows(CodecException.class, () -> ZonedDecimal.decode(hex("404040"), 0, 3, 0));
    }

    @Test
    void signSeparateLeadingMatchesAbndinfo() {
        byte[] b = ZonedDecimal.encodeSignSeparate(new BigDecimal("-42"), 8, 0, true);
        assertArrayEquals(hex("60F0F0F0F0F0F0F4F2"), b);
        assertEquals(new BigDecimal("-42"), ZonedDecimal.decodeSignSeparate(b, 0, 8, 0, true));
    }

    @Test
    void numericOverflowAndLostPrecisionAreRejected() {
        assertThrows(CodecException.class, () -> ZonedDecimal.encode(new BigDecimal("1000"), 3, 0, false));
        assertThrows(CodecException.class, () -> ZonedDecimal.encode(new BigDecimal("1.005"), 6, 2, true));
        assertThrows(CodecException.class, () -> ZonedDecimal.encode(new BigDecimal("-1"), 3, 0, false));
        assertThrows(CodecException.class, () -> PackedDecimal.encode(new BigDecimal("1E10"), 10, 2, true));
    }

    @Test
    void binaryIsBigEndianTwosComplement() {
        assertArrayEquals(hex("000001F0"), Binary.encode(BigDecimal.valueOf(496), 8, 0, true, false));
        assertArrayEquals(hex("FFFFFFFF"), Binary.encode(BigDecimal.valueOf(-1), 9, 0, true, true));
        assertEquals(BigDecimal.valueOf(-1), Binary.decode(hex("FFFF"), 0, 4, 0, true));
        assertEquals(BigDecimal.valueOf(65535), Binary.decode(hex("FFFF"), 0, 4, 0, false));
        assertThrows(CodecException.class, () -> Binary.encode(BigDecimal.valueOf(10000), 4, 0, true, false));
    }

    @Test
    void ebcdicTextIsCp037SpacePaddedAndStrict() {
        Ebcdic e = Ebcdic.cp037();
        assertArrayEquals(hex("C3E4E2E34040"), e.encode("CUST", 6));
        assertArrayEquals(hex("C3E4"), e.encode("CUST", 2)); // COBOL MOVE truncates on the right
        assertThrows(CodecException.class, () -> e.encodeExact("CUST", 2));
        assertEquals("CUST  ", e.decode(hex("C3E4E2E34040"), 0, 6));
        assertArrayEquals(hex("4A5A"), e.encode("¢!", 2)); // CP037-specific code points
    }
}
