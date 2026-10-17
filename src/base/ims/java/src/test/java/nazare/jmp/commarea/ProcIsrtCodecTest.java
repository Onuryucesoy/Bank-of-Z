package nazare.jmp.commarea;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import nazare.jmp.cobol.CobolDate;
import nazare.jmp.cobol.Ebcdic;
import nazare.jmp.cobol.Money;

class ProcIsrtCodecTest {

    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final int S = ProcIsrtCodec.STRUCT_OFFSET;

    static List<ProcIsrtCommarea> oneOfEach() {
        return List.of(
                new ProcIsrtCommarea.Debit(12345678, 987654, Money.of("-25.50")),
                new ProcIsrtCommarea.Credit(12345678, 987654, Money.of("9999999999.99")),
                new ProcIsrtCommarea.TransferLocal(1, 987654, Money.of("0.01"), 99999999),
                new ProcIsrtCommarea.DeleteCustomer(42, 987654, Money.of("-100.00"), new CobolDate(1970, 1, 31),
                        "Mrs Jane Q Public", 1234567890L),
                new ProcIsrtCommarea.CreateCustomer(0, 987654, Money.ZERO, CobolDate.ZERO, "", 9999999999L),
                new ProcIsrtCommarea.DeleteAccount(7, 987654, Money.of("12.34"), new CobolDate(2026, 9, 1),
                        new CobolDate(2026, 10, 1), "ISA", 55L),
                new ProcIsrtCommarea.CreateAccount(8, 987654, Money.of("-0.01"), new CobolDate(2026, 2, 30),
                        new CobolDate(2026, 3, 1), "MORTGAGE", 56L));
    }

    @ParameterizedTest
    @MethodSource("oneOfEach")
    void roundTripsEveryFunctionCode(ProcIsrtCommarea commarea) {
        byte[] bytes = ProcIsrtCodec.encode(commarea);
        assertEquals(131, bytes.length);
        assertEquals(commarea, ProcIsrtCodec.decode(bytes));
        assertArrayEquals(bytes, ProcIsrtCodec.encode(ProcIsrtCodec.decode(bytes)));
    }

    @Test
    void functionCodesAreEbcdicDigits() {
        List<ProcIsrtCommarea> all = oneOfEach();
        for (int i = 0; i < all.size(); i++) {
            assertEquals((byte) (0xF1 + i), ProcIsrtCodec.encode(all.get(i))[0]);
            assertEquals((char) ('1' + i), all.get(i).function().code());
        }
    }

    @Test
    void debitLayout() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.Debit(12345678, 987654, Money.of("-25.50")));
        assertArrayEquals(new byte[4], Arrays.copyOfRange(b, 1, 5), "PCB pointer is written as zeros");
        assertEquals("F1F2F3F4F5F6F7F8", HEX.formatHex(b, S, S + 8));
        assertEquals("F9F8F7F6F5F4", HEX.formatHex(b, S + 8, S + 14));
        assertEquals("F0F0F0F0F0F0F0F0F2F5F5D0", HEX.formatHex(b, S + 14, S + 26));
        for (int i = S + 26; i < b.length; i++) {
            assertEquals(Ebcdic.SPACE, b[i], "FILLER X(100) at " + i);
        }
    }

    @Test
    void transferTargetFollowsAmount() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.TransferLocal(1, 987654, Money.of("0.01"), 87654321));
        assertEquals("F8F7F6F5F4F3F2F1", HEX.formatHex(b, S + 26, S + 34));
    }

    @Test
    void customerLayout() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.CreateCustomer(1, 987654, Money.ZERO,
                new CobolDate(1970, 1, 31), "AB", 1234567890L));
        assertEquals("F1F9F7F0F0F1F3F1", HEX.formatHex(b, S + 26, S + 34), "DOB yyyy mm dd");
        assertEquals("C1C2" + "40".repeat(58), HEX.formatHex(b, S + 34, S + 94), "NAME X(60)");
        assertEquals("F1F2F3F4F5F6F7F8F9F0", HEX.formatHex(b, S + 94, S + 104));
    }

    @Test
    void accountLayout() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.DeleteAccount(1, 987654, Money.ZERO,
                new CobolDate(2026, 9, 1), new CobolDate(2026, 10, 1), "LOAN", 55L));
        assertEquals("F2F0F2F6F0F9F0F1", HEX.formatHex(b, S + 26, S + 34));
        assertEquals("F2F0F2F6F1F0F0F1", HEX.formatHex(b, S + 34, S + 42));
        assertEquals("D3D6C1D540404040", HEX.formatHex(b, S + 42, S + 50), "TYPE X(8) 'LOAN    '");
        assertEquals("F0F0F0F0F0F0F0F0F5F5", HEX.formatHex(b, S + 50, S + 60));
    }

    @Test
    void residueFromAnotherRedefinesMemberIsIgnored() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.Credit(5, 987654, Money.of("1.00")));
        byte[] name = ProcIsrtCodec.encode(new ProcIsrtCommarea.CreateCustomer(5, 987654, Money.of("1.00"),
                CobolDate.ZERO, "LEFTOVER", 1L));
        System.arraycopy(name, S + 26, b, S + 26, ProcIsrtCodec.STRUCT_LENGTH - 26);
        assertEquals(new ProcIsrtCommarea.Credit(5, 987654, Money.of("1.00")), ProcIsrtCodec.decode(b));
    }

    @Test
    void impossibleDatesSurviveTheRoundTrip() {
        var create = new ProcIsrtCommarea.CreateAccount(8, 987654, Money.ZERO, new CobolDate(2026, 2, 30),
                CobolDate.ZERO, "SAVING", 1L);
        var decoded = (ProcIsrtCommarea.CreateAccount) ProcIsrtCodec.decode(ProcIsrtCodec.encode(create));
        assertEquals(new CobolDate(2026, 2, 30), decoded.lastStatement());
        assertThrows(java.time.DateTimeException.class, () -> decoded.lastStatement().toLocalDate());
    }

    @Test
    void decodeRejectsBadInput() {
        byte[] b = ProcIsrtCodec.encode(new ProcIsrtCommarea.Debit(1, 1, Money.ZERO));
        b[0] = (byte) 0xF8;
        assertThrows(IllegalArgumentException.class, () -> ProcIsrtCodec.decode(b), "function '8'");
        assertThrows(IllegalArgumentException.class, () -> ProcIsrtCodec.decode(new byte[130]), "length");
        byte[] spaces = ProcIsrtCodec.encode(new ProcIsrtCommarea.Debit(1, 1, Money.ZERO));
        Arrays.fill(spaces, S, S + 8, Ebcdic.SPACE);
        assertThrows(IllegalArgumentException.class, () -> ProcIsrtCodec.decode(spaces), "spaces in ACCNO");
    }

    @Test
    void constructorsRejectValuesCobolWouldTruncate() {
        assertThrows(IllegalArgumentException.class, () -> new ProcIsrtCommarea.Debit(100_000_000L, 1, Money.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ProcIsrtCommarea.Debit(1, 1_000_000, Money.ZERO));
        assertThrows(ArithmeticException.class, () -> new ProcIsrtCommarea.Debit(1, 1, Money.of("10000000000.00")));
        assertThrows(IllegalArgumentException.class, () -> new ProcIsrtCommarea.CreateCustomer(1, 1, Money.ZERO,
                CobolDate.ZERO, "X".repeat(61), 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcIsrtCommarea.CreateAccount(1, 1, Money.ZERO,
                CobolDate.ZERO, CobolDate.ZERO, "CURRENT  ", 1));
        assertThrows(IllegalArgumentException.class, () -> new ProcIsrtCommarea.DeleteAccount(1, 1, Money.ZERO,
                CobolDate.ZERO, CobolDate.ZERO, "SAVING", 10_000_000_000L));
    }
}
