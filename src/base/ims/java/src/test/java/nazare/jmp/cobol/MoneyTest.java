package nazare.jmp.cobol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @ParameterizedTest(name = "{0} -> TRUNCATE {1}, ROUNDED {2}")
    @CsvSource({
            // input,     COMPUTE X = ..., COMPUTE X ROUNDED = ...
            "10.239,      10.23,           10.24",
            "10.235,      10.23,           10.24",
            "10.2349,     10.23,           10.23",
            "-10.239,     -10.23,          -10.24",
            "-10.235,     -10.23,          -10.24",
            "0.005,       0.00,            0.01",
            "-0.005,      0.00,            -0.01",
            "0.0049,      0.00,            0.00",
            "9999999999.999, 9999999999.99, 10000000000.00",
    })
    void cobolTruncationAndRounding(String input, String truncated, String rounded) {
        BigDecimal value = new BigDecimal(input);
        assertEquals(Money.of(truncated), Money.of(value, CobolRounding.TRUNCATE));
        assertEquals(Money.of(rounded), Money.of(value, CobolRounding.ROUNDED));
    }

    @Test
    void exactFactoryRejectsHiddenRounding() {
        assertThrows(ArithmeticException.class, () -> Money.of("1.234"));
        assertEquals(Money.of("1.23"), Money.of("1.2300"));
    }

    @Test
    void equalityIsNumericAndScaleIsAlwaysTwo() {
        assertEquals(Money.of("1.5"), Money.of("1.50"));
        assertEquals(2, Money.of("7").toBigDecimal().scale());
        assertEquals("7.00", Money.of("7").toPlainString());
        assertEquals(Money.ofCents(-1999), Money.of("-19.99"));
        assertEquals(-1999L, Money.of("-19.99").toCents());
    }

    @Test
    void additionAndSubtractionAreExact() {
        Money balance = Money.of("8830.00");
        assertEquals(Money.of("8829.99"), balance.minus(Money.of("0.01")));
        assertEquals(Money.of("-0.01"), Money.ZERO.minus(Money.of("0.01")));
        assertEquals(Money.of("0.30"), Money.of("0.10").plus(Money.of("0.20")));
        assertEquals(Money.of("-12.34"), Money.of("12.34").negate());
    }

    @Test
    void interestStyleMultiplication() {
        assertEquals(Money.of("12.50"), Money.of("1000.00").times(new BigDecimal("0.0125"), CobolRounding.TRUNCATE));
        // 333.33 * 0.015 = 4.99995
        assertEquals(Money.of("4.99"), Money.of("333.33").times(new BigDecimal("0.015"), CobolRounding.TRUNCATE));
        assertEquals(Money.of("5.00"), Money.of("333.33").times(new BigDecimal("0.015"), CobolRounding.ROUNDED));
        // XFRFUN: COMPUTE HV-PROCTRAN-AMOUNT = COMM-AMT * -1
        assertEquals(Money.of("-25.00"), Money.of("25.00").times(BigDecimal.ONE.negate(), CobolRounding.TRUNCATE));
    }

    @Test
    void sizeLimitsOfS9_10_V99() {
        PicSpec pic = PicSpec.S9_10_V99;
        Money max = Money.of("9999999999.99");
        assertTrue(max.fits(pic));
        assertTrue(max.negate().fits(pic));
        assertFalse(max.plus(Money.of("0.01")).fits(pic));
        assertThrows(ArithmeticException.class, () -> max.plus(Money.of("0.01")).requireFits(pic));
        assertTrue(Money.of("9999999999999.99").fits(PicSpec.S9_13_V99));
    }

    @Test
    void highOrderTruncationWithoutOnSizeError() {
        PicSpec pic = PicSpec.S9_10_V99;
        assertEquals(Money.of("2345678901.23"), Money.of("12345678901.23").truncateHighOrder(pic));
        assertEquals(Money.of("-2345678901.23"), Money.of("-12345678901.23").truncateHighOrder(pic));
        assertEquals(Money.ZERO, Money.of("9999999999.99").plus(Money.of("0.01")).truncateHighOrder(pic));
        assertEquals(Money.of("5.00"), Money.of("-5.00").truncateHighOrder(PicSpec.unsigned(10, 2)));
    }

    @Test
    void picturesWithOtherScalesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Money.of("1.00").fits(PicSpec.signed(8, 0)));
    }
}
