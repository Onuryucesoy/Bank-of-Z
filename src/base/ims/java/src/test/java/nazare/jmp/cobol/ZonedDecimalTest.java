package nazare.jmp.cobol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class ZonedDecimalTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();

    @Test
    void signedAmountUsesTrailingOverpunch() {
        assertArrayEquals(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4C5"), ZonedDecimal.encode(new BigDecimal("123.45"), PicSpec.S9_10_V99));
        assertArrayEquals(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4D5"), ZonedDecimal.encode(new BigDecimal("-123.45"), PicSpec.S9_10_V99));
        assertArrayEquals(HEX.parseHex("F0F0F0F0F0F0F0F0F0F0F0C0"), ZonedDecimal.encode(new BigDecimal("0.00"), PicSpec.S9_10_V99));
    }

    @Test
    void unsignedFieldsUseZoneF() {
        byte[] out = new byte[8];
        ZonedDecimal.encodeUnsigned(12345678, 8, out, 0);
        assertArrayEquals(HEX.parseHex("F1F2F3F4F5F6F7F8"), out);
        assertEquals(12345678L, ZonedDecimal.decodeUnsigned(out, 0, 8));
    }

    @Test
    void decodesAllValidSignZones() {
        assertEquals(new BigDecimal("123.45"), ZonedDecimal.decode(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4F5"), 0, PicSpec.S9_10_V99));
        assertEquals(new BigDecimal("123.45"), ZonedDecimal.decode(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4C5"), 0, PicSpec.S9_10_V99));
        assertEquals(new BigDecimal("-123.45"), ZonedDecimal.decode(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4D5"), 0, PicSpec.S9_10_V99));
        assertEquals(new BigDecimal("-123.45"), ZonedDecimal.decode(HEX.parseHex("F0F0F0F0F0F0F0F1F2F3F4B5"), 0, PicSpec.S9_10_V99));
    }

    @Test
    void spacesAndLowValuesInNumericFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decodeUnsigned(HEX.parseHex("4040404040404040"), 0, 8));
        assertThrows(IllegalArgumentException.class, () -> ZonedDecimal.decodeUnsigned(HEX.parseHex("0000000000000000"), 0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> ZonedDecimal.decode(HEX.parseHex("C0F0F0F0F0F0F0F1F2F3F4C5"), 0, PicSpec.S9_10_V99), "sign zone not in last byte");
    }

    @Test
    void encodeRefusesOverflow() {
        assertThrows(ArithmeticException.class, () -> ZonedDecimal.encodeUnsigned(100_000_000L, 8, new byte[8], 0));
        assertThrows(ArithmeticException.class, () -> ZonedDecimal.encode(new BigDecimal("1.234"), PicSpec.S9_10_V99));
    }
}
