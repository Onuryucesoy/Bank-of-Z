package com.bankofz.migration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class DtoValidationTest {

    private static final Address ADDRESS = new Address("1 High St", "", "London", "SW1A 1AA", "United Kingdom");

    private static AccountRecord account(String rate, String overdraft, String available) {
        return new AccountRecord("1", "987654", "1", "CURRENT", new BigDecimal(rate), LocalDate.of(2020, 1, 1),
                new BigDecimal(overdraft), null, null, new BigDecimal(available), new BigDecimal(available));
    }

    private static CustomerRecord customer(String number, String lastName, int creditScore) {
        return new CustomerRecord("987654", number, "Mr", "John", lastName, LocalDate.of(1980, 5, 17),
                "0123", ADDRESS, CustomerStatus.ACTIVE, LocalDate.of(2020, 1, 1), creditScore, null);
    }

    @Test
    void moneyIsNormalisedToPicScale() {
        AccountRecord a = account("1.5", "250", "12.5");
        assertEquals(new BigDecimal("1.50"), a.interestRate());
        assertEquals(2, a.availableBalance().scale());
        assertEquals("12.50", a.actualBalance().toPlainString());
        assertEquals(0, a.overdraftLimit().scale());
        assertEquals("00000001", a.accountNumber());
        assertEquals("0000000001", a.customerNumber());
    }

    @Test
    void moneyIsNeverRounded() {
        assertThrows(IllegalArgumentException.class, () -> account("0", "0", "1.234"));
        assertThrows(IllegalArgumentException.class, () -> account("0", "0.5", "0"));
    }

    @Test
    void balanceRangeIsS9_10_V99() {
        assertEquals(new BigDecimal("9999999999.99"), account("0", "0", "9999999999.99").availableBalance());
        assertEquals(new BigDecimal("-9999999999.99"), account("0", "0", "-9999999999.99").availableBalance());
        assertThrows(IllegalArgumentException.class, () -> account("0", "0", "10000000000.00"));
    }

    @Test
    void unsignedPicsRejectNegativesAndOverflow() {
        assertEquals(new BigDecimal("9999.99"), account("9999.99", "0", "0").interestRate());
        assertThrows(IllegalArgumentException.class, () -> account("-0.01", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> account("10000", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> account("0", "100000000", "0"));
    }

    @Test
    void keysArePaddedDigitStrings() {
        assertEquals("0000000042", customer("42", "Higins", 0).customerNumber());
        assertThrows(IllegalArgumentException.class, () -> customer("12345678901", "x", 0));
        assertThrows(IllegalArgumentException.class, () -> customer("12A", "x", 0));
        assertThrows(IllegalArgumentException.class, () -> customer("-1", "x", 0));
    }

    @Test
    void textIsBoundedByPicLengthAndUnpadded() {
        assertEquals("Higins", customer("1", "Higins    ", 0).lastName());
        assertEquals(50, customer("1", "x".repeat(50), 0).lastName().length());
        assertThrows(IllegalArgumentException.class, () -> customer("1", "x".repeat(51), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Address("", "", "", "12345678901", ""));
    }

    @Test
    void creditScoreIsPic999() {
        assertEquals(999, customer("1", "x", 999).creditScore());
        assertThrows(IllegalArgumentException.class, () -> customer("1", "x", 1000));
        assertThrows(IllegalArgumentException.class, () -> customer("1", "x", -1));
    }

    @Test
    void customerStatusMapsPaddedColumnValues() {
        assertEquals(CustomerStatus.SUSPENDED, CustomerStatus.fromCobol("SUSPENDED "));
        assertEquals(CustomerStatus.ACTIVE, CustomerStatus.fromCobol("ACTIVE    "));
        assertThrows(IllegalArgumentException.class, () -> CustomerStatus.fromCobol("active"));
        assertThrows(IllegalArgumentException.class, () -> CustomerStatus.fromCobol("          "));
    }

    @Test
    void counterInterfaces() {
        assertEquals(CounterFunction.ROLLBACK, CounterFunction.fromCode('R'));
        assertThrows(IllegalArgumentException.class, () -> CounterFunction.fromCode('X'));
        assertEquals("0000000000", NewCustomerNumberRequest.getNew().customerNumber());
        assertEquals("0000000007", NewCustomerNumberRequest.rollback("7").customerNumber());
        assertEquals(CounterFunction.CURRENT, NewCustomerNumberRequest.current().function());
        assertEquals("00000000", new NewAccountNumberRequest(CounterFunction.GETNEW, null).accountNumber());
        NewCustomerNumberResponse ok = new NewCustomerNumberResponse(CounterFunction.GETNEW, "43", true, ' ');
        assertEquals('Y', ok.successFlag());
        assertThrows(IllegalArgumentException.class,
                () -> new NewCustomerNumberResponse(CounterFunction.GETNEW, "43", true, '1'));
        assertThrows(IllegalArgumentException.class,
                () -> new NewAccountNumberResponse(CounterFunction.GETNEW, "123456789", true, ' '));
    }

    @Test
    void imsTransactionAmountIsPackedScale2() {
        LocalDateTime ts = CobolDates.fromImsTimestamp("2003-07-23 17:56:00.64");
        BankTransactionRecord t = new BankTransactionRecord(1010000000001L, ts, 'd', new BigDecimal("8830"), 0, 101);
        assertEquals(new BigDecimal("8830.00"), t.amount());
        assertThrows(IllegalArgumentException.class,
                () -> new BankTransactionRecord(1, ts, 'x', BigDecimal.ONE, 0, 101));
        assertThrows(IllegalArgumentException.class,
                () -> new BankTransactionRecord(1, ts, 'w', new BigDecimal("10000000000000.00"), 0, 101));
    }
}
