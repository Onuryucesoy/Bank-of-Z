package nazare.jmp.cobol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PackedDecimalTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    @ParameterizedTest(name = "{1} as {0} COMP-3 = X''{2}''")
    @CsvSource({
            "S13V2, 12345.67,           000000001234567C",
            "S13V2, -12345.67,          000000001234567D",
            "S13V2, 0.00,               000000000000000C",
            "S13V2, 9999999999999.99,   999999999999999C",
            "S10V2, -1.00,              0000000000100D",
            "S10V2, 9999999999.99,      0999999999999C",
            "S4V2,  1.25,               0000125C",
            "U4V2,  1.25,               0000125F",
    })
    void encodesAndDecodes(String picName, String value, String hex) {
        PicSpec pic = pic(picName);
        BigDecimal v = new BigDecimal(value);
        byte[] bytes = HEX.parseHex(hex);
        assertEquals(pic.packedLength(), bytes.length);
        assertArrayEquals(bytes, PackedDecimal.encode(v, pic));
        assertEquals(v, PackedDecimal.decode(bytes, pic));
    }

    @Test
    void lengthsMatchTheDbdAndCopybooks() {
        assertEquals(8, PicSpec.S9_13_V99.packedLength(), "DBD BALANCE/AMOUNT BYTES=8");
        assertEquals(7, PicSpec.S9_10_V99.packedLength());
        assertEquals(4, PicSpec.S9_4_V99.packedLength());
    }

    @Test
    void acceptsNonPreferredSigns() {
        assertEquals(new BigDecimal("123.45"), PackedDecimal.decode(HEX.parseHex("000000000012345F"), PicSpec.S9_13_V99));
        assertEquals(new BigDecimal("123.45"), PackedDecimal.decode(HEX.parseHex("000000000012345A"), PicSpec.S9_13_V99));
        assertEquals(new BigDecimal("-123.45"), PackedDecimal.decode(HEX.parseHex("000000000012345B"), PicSpec.S9_13_V99));
    }

    @Test
    void negativeZeroDecodesAsZero() {
        assertEquals(0, PackedDecimal.decode(HEX.parseHex("000000000000000D"), PicSpec.S9_13_V99).signum());
    }

    @Test
    void invalidDataIsRejectedLikeS0C7() {
        assertThrows(IllegalArgumentException.class,
                () -> PackedDecimal.decode(HEX.parseHex("4040404040404040"), PicSpec.S9_13_V99), "spaces");
        assertThrows(IllegalArgumentException.class,
                () -> PackedDecimal.decode(HEX.parseHex("0000000000000000"), PicSpec.S9_13_V99), "low-values: sign 0");
        assertThrows(IllegalArgumentException.class,
                () -> PackedDecimal.decode(HEX.parseHex("00000000000A000C"), PicSpec.S9_13_V99), "digit nibble A");
        assertThrows(IllegalArgumentException.class,
                () -> PackedDecimal.decode(HEX.parseHex("1000000000100D"), PicSpec.S9_10_V99), "pad nibble");
    }

    @Test
    void encodeRefusesOverflowAndExtraDecimals() {
        assertThrows(ArithmeticException.class, () -> PackedDecimal.encode(new BigDecimal("10000000000.00"), PicSpec.S9_10_V99));
        assertThrows(ArithmeticException.class, () -> PackedDecimal.encode(new BigDecimal("1.234"), PicSpec.S9_10_V99));
        assertThrows(ArithmeticException.class, () -> PackedDecimal.encode(new BigDecimal("-1.00"), PicSpec.U9_4_V99));
    }

    private static PicSpec pic(String name) {
        return switch (name) {
            case "S13V2" -> PicSpec.S9_13_V99;
            case "S10V2" -> PicSpec.S9_10_V99;
            case "S4V2" -> PicSpec.S9_4_V99;
            case "U4V2" -> PicSpec.U9_4_V99;
            default -> throw new IllegalArgumentException(name);
        };
    }
}
