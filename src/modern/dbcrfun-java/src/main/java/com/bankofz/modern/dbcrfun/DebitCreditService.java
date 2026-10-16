package com.bankofz.modern.dbcrfun;

import com.bankofz.modern.dbcrfun.DebitCreditResult.FailCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Java port of the CICS program DBCRFUN (src/base/cics/cobol/DBCRFUN.cbl): applies a teller or payment
 * debit/credit to an ACCOUNT row and records it in PROCTRAN, all-or-nothing.
 *
 * <p>Behaviour is kept identical to the COBOL, including its quirks; each one is marked "Legacy parity".
 */
public final class DebitCreditService {

    /** SORTCODE copybook (src/base/cics/copy/SORTCODE.cpy). */
    public static final String SORTCODE = "987654";

    private static final BigDecimal S9_10_LIMIT = BigDecimal.TEN.pow(10);
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private final DataSource dataSource;
    private final Clock clock;
    private final LongSupplier taskNumber;

    /**
     * @param taskNumber stands in for EIBTASKN, which DBCRFUN writes to PROCTRAN_REF
     */
    public DebitCreditService(DataSource dataSource, Clock clock, LongSupplier taskNumber) {
        this.dataSource = dataSource;
        this.clock = clock;
        this.taskNumber = taskNumber;
    }

    public DebitCreditResult apply(DebitCreditRequest request) {
        BigDecimal amount = toS9_10V99(request.amount());
        String accountNumber = leftPadZeros(request.accountNumber(), 8);

        try (Connection con = dataSource.getConnection()) {
            con.setAutoCommit(false);
            try {
                DebitCreditResult result = applyInTransaction(con, request, accountNumber, amount);
                if (result.success()) {
                    con.commit();
                } else {
                    con.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            return fail(FailCode.DATASTORE_ERROR, null, null);
        }
    }

    private DebitCreditResult applyInTransaction(Connection con, DebitCreditRequest request,
                                                 String accountNumber, BigDecimal amount) throws SQLException {
        // DBCRFUN.cbl:245-295. Legacy parity: a plain SELECT followed by an UPDATE of absolute values; under the
        // ISOLATION(CS) bind (.setup/build/languages/Db2Binds.yaml) two concurrent requests can lose an update.
        Account account;
        try {
            account = selectAccount(con, accountNumber);
        } catch (SQLException e) {
            return fail(FailCode.DATASTORE_ERROR, null, null);
        }
        if (account == null) {
            return fail(FailCode.ACCOUNT_NOT_FOUND, null, null);
        }

        boolean mortgageOrLoan = account.type.equals("MORTGAGE") || account.type.equals("LOAN");

        if (amount.signum() < 0) {
            // DBCRFUN.cbl:330-338
            if (mortgageOrLoan && request.isPayment()) {
                return fail(FailCode.PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE, null, null);
            }
            // DBCRFUN.cbl:340-350. Legacy parity: only payments are checked, against zero rather than the
            // overdraft limit (ACCOUNT_OVERDRAFT_LIMIT is read but never used). Teller debits can overdraw.
            BigDecimal difference = toS9_10V99(account.availableBalance.add(amount));
            if (difference.signum() < 0 && request.isPayment()) {
                return fail(FailCode.INSUFFICIENT_FUNDS, null, null);
            }
        }

        // DBCRFUN.cbl:368-376. Legacy parity: this also rejects payment CREDITS into mortgage/loan accounts.
        if (mortgageOrLoan && request.isPayment()) {
            return fail(FailCode.PAYMENT_NOT_ALLOWED_FOR_ACCOUNT_TYPE, null, null);
        }

        // DBCRFUN.cbl:384-387. Legacy parity: COMPUTE without ON SIZE ERROR drops high-order digits.
        BigDecimal newAvailable = toS9_10V99(account.availableBalance.add(amount));
        BigDecimal newActual = toS9_10V99(account.actualBalance.add(amount));

        // DBCRFUN.cbl:393-430. COMM-AV-BAL/COMM-ACT-BAL are set before SQLCODE is checked.
        try {
            updateBalances(con, accountNumber, newAvailable, newActual);
        } catch (SQLException e) {
            return fail(FailCode.DATASTORE_ERROR, newAvailable, newActual);
        }

        // DBCRFUN.cbl:440-660
        try {
            insertProcTran(con, request, accountNumber, amount);
        } catch (SQLException e) {
            // Legacy parity: the COBOL moves '02' into the 1-byte COMM-FAIL-CODE (line 645), so callers receive
            // '0'. The ACCOUNT update is rolled back but the returned balances still show the new values.
            return fail(FailCode.NONE, newAvailable, newActual);
        }

        return new DebitCreditResult(true, FailCode.NONE, SORTCODE, newAvailable, newActual);
    }

    private Account selectAccount(Connection con, String accountNumber) throws SQLException {
        String sql = "SELECT ACCOUNT_TYPE, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, SORTCODE);
            ps.setString(2, accountNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String type = rs.getString(1);
                return new Account(type == null ? "" : type.stripTrailing(), rs.getBigDecimal(2), rs.getBigDecimal(3));
            }
        }
    }

    private void updateBalances(Connection con, String accountNumber, BigDecimal available, BigDecimal actual)
            throws SQLException {
        String sql = "UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?, ACCOUNT_ACTUAL_BALANCE = ?"
                + " WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setBigDecimal(1, available);
            ps.setBigDecimal(2, actual);
            ps.setString(3, SORTCODE);
            ps.setString(4, accountNumber);
            ps.executeUpdate();
        }
    }

    private void insertProcTran(Connection con, DebitCreditRequest request, String accountNumber, BigDecimal amount)
            throws SQLException {
        LocalDateTime now = LocalDateTime.now(clock);
        String sql = "INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE,"
                + " PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, "PRTR");
            ps.setString(2, SORTCODE);
            ps.setString(3, accountNumber);
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, leftPadZeros(Long.toString(taskNumber.getAsLong()), 12));
            ps.setString(7, procTranType(amount, request));
            ps.setString(8, procTranDescription(amount, request));
            ps.setBigDecimal(9, amount);
            ps.executeUpdate();
        }
    }

    /** DBCRFUN.cbl:491-525: DEB/CRE for teller requests, PDR/PCR for payments. */
    static String procTranType(BigDecimal amount, DebitCreditRequest request) {
        boolean debit = amount.signum() < 0;
        if (request.isPayment()) {
            return debit ? "PDR" : "PCR";
        }
        return debit ? "DEB" : "CRE";
    }

    /**
     * DBCRFUN.cbl:491-525. Payments use COMM-DESCRIPTION, or COMM-ORIGIN(1:14) when it is blank, which is the
     * 8-byte APPLID followed by the first 6 bytes of USERID.
     */
    static String procTranDescription(BigDecimal amount, DebitCreditRequest request) {
        if (!request.isPayment()) {
            return amount.signum() < 0 ? "COUNTER WTHDRW" : "COUNTER RECVED";
        }
        String description = request.description();
        if (description != null && !description.isBlank()) {
            return description.length() > 40 ? description.substring(0, 40) : description;
        }
        return (padRight(request.applId(), 8) + padRight(request.userId(), 8)).substring(0, 14);
    }

    /** Assignment to PIC S9(10)V99: extra decimals truncated, high-order digits dropped. */
    static BigDecimal toS9_10V99(BigDecimal value) {
        return value.setScale(2, RoundingMode.DOWN).remainder(S9_10_LIMIT);
    }

    private static DebitCreditResult fail(char failCode, BigDecimal available, BigDecimal actual) {
        return new DebitCreditResult(false, failCode, SORTCODE, available, actual);
    }

    private static String leftPadZeros(String value, int width) {
        String v = value == null ? "" : value.strip();
        return v.length() >= width ? v : "0".repeat(width - v.length()) + v;
    }

    private static String padRight(String value, int width) {
        String v = value == null ? "" : value;
        return v.length() >= width ? v.substring(0, width) : v + " ".repeat(width - v.length());
    }

    private record Account(String type, BigDecimal availableBalance, BigDecimal actualBalance) {
    }
}
