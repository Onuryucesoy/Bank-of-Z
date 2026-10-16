package com.bankofz.modern.dbcrfun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bankofz.modern.dbcrfun.DebitCreditResult.FailCode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Characterisation tests for the DBCRFUN port. Each test pins one behaviour of
 * src/base/cics/cobol/DBCRFUN.cbl; line numbers refer to that file.
 */
class DebitCreditServiceTest {

    private static final String ACC = "00000042";
    private static final Clock CLOCK =
            Clock.fixed(ZonedDateTime.of(2026, 10, 15, 10, 30, 15, 0, ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    private JdbcDataSource ds;
    private DebitCreditService service;

    @BeforeEach
    void setUp() throws Exception {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (String ddl : readSchema().split(";")) {
                if (!ddl.isBlank()) {
                    s.execute(ddl);
                }
            }
        }
        service = new DebitCreditService(ds, CLOCK, () -> 4242L);
    }

    @Test
    @DisplayName("Teller credit adds to both balances and logs CRE / COUNTER RECVED (lines 384-387, 508-510)")
    void tellerCredit() throws Exception {
        insertAccount(ACC, "CURRENT", "100.00", "120.00", 0);

        DebitCreditResult r = service.apply(DebitCreditRequest.teller(ACC, new BigDecimal("25.50")));

        assertTrue(r.success());
        assertEquals(FailCode.NONE, r.failCode());
        assertEquals(new BigDecimal("125.50"), r.availableBalance());
        assertEquals(new BigDecimal("145.50"), r.actualBalance());
        assertBalances(ACC, "125.50", "145.50");
        List<String[]> tx = procTran();
        assertEquals(1, tx.size());
        assertEquals("CRE", tx.get(0)[0]);
        assertEquals("COUNTER RECVED", tx.get(0)[1]);
        assertEquals("25.50", tx.get(0)[2]);
    }

    @Test
    @DisplayName("Teller debit is never balance-checked and may overdraw (lines 340-350 only apply to payments)")
    void tellerDebitMayOverdraw() throws Exception {
        insertAccount(ACC, "CURRENT", "10.00", "10.00", 0);

        DebitCreditResult r = service.apply(DebitCreditRequest.teller(ACC, new BigDecimal("-500.00")));

        assertTrue(r.success());
        assertBalances(ACC, "-490.00", "-490.00");
        assertEquals("DEB", procTran().get(0)[0]);
        assertEquals("COUNTER WTHDRW", procTran().get(0)[1]);
    }

    @Test
    @DisplayName("Payment debit beyond the available balance fails with code 3 and changes nothing (lines 340-350)")
    void paymentDebitInsufficientFunds() throws Exception {
        insertAccount(ACC, "CURRENT", "10.00", "10.00", 0);

        DebitCreditResult r = service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("-10.01"), "RENT"));

        assertFalse(r.success());
        assertEquals(FailCode.INSUFFICIENT_FUNDS, r.failCode());
        assertNull(r.availableBalance());
        assertBalances(ACC, "10.00", "10.00");
        assertTrue(procTran().isEmpty());
    }

    @Test
    @DisplayName("Payment debit down to exactly zero is allowed (check is difference < 0, line 344)")
    void paymentDebitToZero() throws Exception {
        insertAccount(ACC, "CURRENT", "10.00", "10.00", 0);

        DebitCreditResult r = service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("-10.00"), "RENT"));

        assertTrue(r.success());
        assertBalances(ACC, "0.00", "0.00");
        assertEquals("PDR", procTran().get(0)[0]);
    }

    @Test
    @DisplayName("Overdraft limit is read but ignored: payment into an arranged overdraft is still refused (line 344)")
    void overdraftLimitIgnored() throws Exception {
        insertAccount(ACC, "CURRENT", "10.00", "10.00", 1000);

        DebitCreditResult r = service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("-50.00"), "RENT"));

        assertEquals(FailCode.INSUFFICIENT_FUNDS, r.failCode());
    }

    @Test
    @DisplayName("Payment debit from MORTGAGE or LOAN fails with code 4 (lines 330-338)")
    void paymentDebitFromMortgageOrLoan() throws Exception {
        insertAccount(ACC, "MORTGAGE", "5000.00", "5000.00", 0);
        insertAccount("00000043", "LOAN", "5000.00", "5000.00", 0);

        assertEquals(FailCode.PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE,
                service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("-1.00"), "X")).failCode());
        assertEquals(FailCode.PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE,
                service.apply(DebitCreditRequest.payment("00000043", new BigDecimal("-1.00"), "X")).failCode());
        assertTrue(procTran().isEmpty());
    }

    @Test
    @DisplayName("Legacy parity: payment CREDIT into MORTGAGE/LOAN is also refused with code 4 (lines 368-376)")
    void paymentCreditToLoanRefused() throws Exception {
        insertAccount(ACC, "LOAN", "-9000.00", "-9000.00", 0);

        DebitCreditResult r = service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("100.00"), "REPAYMENT"));

        assertFalse(r.success());
        assertEquals(FailCode.PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE, r.failCode());
        assertBalances(ACC, "-9000.00", "-9000.00");
    }

    @Test
    @DisplayName("Teller requests against MORTGAGE/LOAN are allowed (checks only apply to FACILTYPE 496)")
    void tellerAgainstLoanAllowed() throws Exception {
        insertAccount(ACC, "LOAN", "100.00", "100.00", 0);

        assertTrue(service.apply(DebitCreditRequest.teller(ACC, new BigDecimal("-30.00"))).success());
        assertBalances(ACC, "70.00", "70.00");
    }

    @Test
    @DisplayName("Payment description: COMM-DESCRIPTION, else COMM-ORIGIN(1:14) = APPLID + 6 bytes of USERID")
    void paymentDescription() throws Exception {
        insertAccount(ACC, "CURRENT", "100.00", "100.00", 0);

        service.apply(DebitCreditRequest.payment(ACC, new BigDecimal("1.00"), "SALARY OCTOBER"));
        service.apply(new DebitCreditRequest(ACC, new BigDecimal("2.00"), null, 496,
                "CICSA", "WEBUSER01", "", "", "   "));

        List<String[]> tx = procTran();
        assertEquals("PCR", tx.get(0)[0]);
        assertEquals("SALARY OCTOBER", tx.get(0)[1]);
        assertEquals("CICSA   WEBUSE", tx.get(1)[1]);
    }

    @Test
    @DisplayName("Zero amount takes the credit path (line 491: only < 0 is a debit)")
    void zeroAmountIsCredit() throws Exception {
        insertAccount(ACC, "CURRENT", "1.00", "1.00", 0);

        assertTrue(service.apply(DebitCreditRequest.teller(ACC, BigDecimal.ZERO)).success());
        assertEquals("CRE", procTran().get(0)[0]);
    }

    @Test
    @DisplayName("Unknown account fails with code 1, balances untouched (lines 279-284)")
    void unknownAccount() {
        DebitCreditResult r = service.apply(DebitCreditRequest.teller("99999999", new BigDecimal("1.00")));

        assertFalse(r.success());
        assertEquals(FailCode.ACCOUNT_NOT_FOUND, r.failCode());
        assertNull(r.availableBalance());
        assertNull(r.actualBalance());
    }

    @Test
    @DisplayName("Caller's sort code is ignored; the SORTCODE copybook value 987654 is always used (lines 211-212)")
    void sortCodeIgnored() throws Exception {
        insertAccount(ACC, "CURRENT", "1.00", "1.00", 0);
        insertAccountAt("111111", "00000077", "CURRENT", "1.00", "1.00");

        DebitCreditRequest toOtherBranch =
                new DebitCreditRequest("00000077", new BigDecimal("1.00"), "111111", 0, "", "", "", "", "");
        DebitCreditResult r = service.apply(toOtherBranch);

        assertEquals(FailCode.ACCOUNT_NOT_FOUND, r.failCode());
        assertEquals("987654", r.sortCode());
    }

    @Test
    @DisplayName("Account number is zero-padded to 8 digits, as the z/OS Connect deposit mapping does")
    void accountNumberPadded() throws Exception {
        insertAccount(ACC, "CURRENT", "1.00", "1.00", 0);

        assertTrue(service.apply(DebitCreditRequest.teller("42", new BigDecimal("1.00"))).success());
    }

    @Test
    @DisplayName("PROCTRAN row: eyecatcher PRTR, sort code, date, HHMMSS time, 12-digit task number ref")
    void procTranRecord() throws Exception {
        insertAccount(ACC, "CURRENT", "1.00", "1.00", 0);
        service.apply(DebitCreditRequest.teller(ACC, new BigDecimal("1.00")));

        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER,"
                     + " PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF FROM PROCTRAN")) {
            assertTrue(rs.next());
            assertEquals("PRTR", rs.getString(1));
            assertEquals("987654", rs.getString(2));
            assertEquals(ACC, rs.getString(3));
            assertEquals(LocalDate.of(2026, 10, 15), rs.getDate(4).toLocalDate());
            assertEquals("103015", rs.getString(5));
            assertEquals("000000004242", rs.getString(6));
        }
    }

    @Test
    @DisplayName("PROCTRAN insert failure rolls back the balance update; legacy fail code reaches caller as '0' (line 645)")
    void procTranFailureRollsBack() throws Exception {
        insertAccount(ACC, "CURRENT", "100.00", "100.00", 0);
        exec("ALTER TABLE PROCTRAN RENAME TO PROCTRAN_OFFLINE");

        DebitCreditResult r = service.apply(DebitCreditRequest.teller(ACC, new BigDecimal("-40.00")));

        assertFalse(r.success());
        assertEquals(FailCode.NONE, r.failCode());
        assertEquals(new BigDecimal("60.00"), r.availableBalance());
        assertBalances(ACC, "100.00", "100.00");
    }

    @Test
    @DisplayName("PIC S9(10)V99 arithmetic: extra decimals truncated, high-order digits dropped")
    void cobolDecimalSemantics() {
        assertEquals(new BigDecimal("10.41"), DebitCreditService.toS9_10V99(new BigDecimal("10.419")));
        assertEquals(new BigDecimal("-10.41"), DebitCreditService.toS9_10V99(new BigDecimal("-10.419")));
        assertEquals(new BigDecimal("2345678901.23"), DebitCreditService.toS9_10V99(new BigDecimal("12345678901.23")));
        assertEquals(new BigDecimal("-0.01"), DebitCreditService.toS9_10V99(new BigDecimal("-10000000000.01")));
    }

    private void insertAccount(String number, String type, String available, String actual, int overdraft)
            throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ACCOUNT (ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, ACCOUNT_NUMBER,"
                        + " ACCOUNT_TYPE, ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE)"
                        + " VALUES ('ACCT', '0000000001', '987654', ?, ?, ?, ?, ?)")) {
            ps.setString(1, number);
            ps.setString(2, type);
            ps.setInt(3, overdraft);
            ps.setBigDecimal(4, new BigDecimal(available));
            ps.setBigDecimal(5, new BigDecimal(actual));
            ps.executeUpdate();
        }
    }

    private void insertAccountAt(String sortCode, String number, String type, String available, String actual)
            throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_AVAILABLE_BALANCE,"
                        + " ACCOUNT_ACTUAL_BALANCE) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sortCode);
            ps.setString(2, number);
            ps.setString(3, type);
            ps.setBigDecimal(4, new BigDecimal(available));
            ps.setBigDecimal(5, new BigDecimal(actual));
            ps.executeUpdate();
        }
    }

    private void assertBalances(String number, String available, String actual) throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                        + " WHERE ACCOUNT_SORTCODE = '987654' AND ACCOUNT_NUMBER = ?")) {
            ps.setString(1, number);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal(available), rs.getBigDecimal(1));
                assertEquals(new BigDecimal(actual), rs.getBigDecimal(2));
            }
        }
    }

    private List<String[]> procTran() throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT FROM PROCTRAN"
                     + " ORDER BY PROCTRAN_AMOUNT")) {
            while (rs.next()) {
                rows.add(new String[] {rs.getString(1), rs.getString(2).stripTrailing(), rs.getBigDecimal(3).toPlainString()});
            }
        }
        return rows;
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String readSchema() throws IOException {
        try (InputStream in = DebitCreditServiceTest.class.getResourceAsStream("/schema.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        }
    }
}
