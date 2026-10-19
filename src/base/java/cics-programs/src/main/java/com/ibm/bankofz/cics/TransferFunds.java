package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Xfrfun;
import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * XFRFUN over the 90-byte XFRFUN COMMAREA. The lower account number is updated first (lock ordering), the FROM
 * account may go negative, a missing FROM account is fail 1 and a missing TO account fail 2 (both SYNCPOINT
 * ROLLBACK), other SQL failures abend FROM / 'TO  ' / RUF2 / RUF3, and two 'TFR' PROCTRAN rows are written
 * (abends WPCD / WPCT). The COBOL retries Db2 deadlocks (-911, reason 00C90088) up to five times with
 * SYNCPOINT + DELAY; this port abends instead and leaves the retry to the caller.
 */
public final class TransferFunds {
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private TransferFunds() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        Text.encode(UpdateAccount.SORTCODE, comm, Xfrfun.COMM_FSCODE.offset(), 6, Ebcdic.CP037);
        Text.encode(UpdateAccount.SORTCODE, comm, Xfrfun.COMM_TSCODE.offset(), 6, Ebcdic.CP037);
        BigDecimal amt = zoned(comm, Xfrfun.COMM_AMT);
        if (amt.signum() <= 0) {
            Text.encode("4N", comm, Xfrfun.COMM_FAIL_CODE.offset(), 2, Ebcdic.CP037);
            return;
        }
        success(comm, 'N');
        String from = text(comm, Xfrfun.COMM_FACCNO);
        String to = text(comm, Xfrfun.COMM_TACCNO);
        if (from.equals(to)) {
            throw task.abend("SAME");
        }
        if (from.compareTo(to) < 0) {
            if (!fromLeg(comm, con, task, amt) || !toLeg(comm, con, task, amt)) {
                return;
            }
        } else if (!toLeg(comm, con, task, amt) || !fromLeg(comm, con, task, amt)) {
            return;
        }
        LocalDateTime now = task.now();
        proctran(con, task, now, from, amt.negate(), to, "WPCD");
        proctran(con, task, now, to, amt, from, "WPCT");
        success(comm, 'Y');
    }

    private static boolean fromLeg(byte[] comm, Connection con, CicsTask task, BigDecimal amt) {
        char fail = update(comm, con, Xfrfun.COMM_FACCNO, amt.negate(), Xfrfun.COMM_FAVBAL, Xfrfun.COMM_FACTBAL);
        if (fail == '1') {
            task.rollback();
            return false;
        }
        if (fail != ' ') {
            throw task.abend("FROM");
        }
        return true;
    }

    private static boolean toLeg(byte[] comm, Connection con, CicsTask task, BigDecimal amt) {
        char fail = update(comm, con, Xfrfun.COMM_TACCNO, amt, Xfrfun.COMM_TAVBAL, Xfrfun.COMM_TACTBAL);
        if (fail == '1') {
            Text.encode("2", comm, Xfrfun.COMM_FAIL_CODE.offset(), 1, Ebcdic.CP037);
            task.rollback();
            return false;
        }
        if (fail != ' ') {
            throw task.abend(fail == 'S' ? "RUF2" : "RUF3");
        }
        return true;
    }

    /** Returns ' ' on success, '1' for no row, 'S' / '3' for a SELECT / UPDATE failure (fail code set to 1 or 3). */
    private static char update(byte[] comm, Connection con, FieldSpec acc, BigDecimal delta, FieldSpec av,
            FieldSpec act) {
        success(comm, 'N');
        String accno = text(comm, acc);
        BigDecimal avail;
        BigDecimal actual;
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE"
                + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")) {
            ps.setString(1, UpdateAccount.SORTCODE);
            ps.setString(2, accno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail(comm, '1');
                    return '1';
                }
                avail = rs.getBigDecimal(1);
                actual = rs.getBigDecimal(2);
            }
            if (avail == null || actual == null) {
                throw new SQLException("SQLCODE -305", "22002", -305);
            }
        } catch (SQLException e) {
            fail(comm, '3');
            return 'S';
        }
        avail = Assign.fit(avail.add(delta), 12, 2, true, false);
        actual = Assign.fit(actual.add(delta), 12, 2, true, false);
        try (PreparedStatement ps = con.prepareStatement("UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?,"
                + " ACCOUNT_ACTUAL_BALANCE = ? WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")) {
            ps.setBigDecimal(1, avail);
            ps.setBigDecimal(2, actual);
            ps.setString(3, UpdateAccount.SORTCODE);
            ps.setString(4, accno);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("SQLCODE +100", "02000", 100);
            }
        } catch (SQLException e) {
            fail(comm, '3');
            return '3';
        }
        ZonedDecimal.encode(avail, comm, av.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        ZonedDecimal.encode(actual, comm, act.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        success(comm, 'Y');
        return ' ';
    }

    private static void proctran(Connection con, CicsTask task, LocalDateTime now, String accno, BigDecimal amount,
            String other, String abend) {
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, UpdateAccount.SORTCODE);
            ps.setString(3, accno);
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, "TFR");
            ps.setString(8, String.format("%-26s", "TRANSFER") + UpdateAccount.SORTCODE + other);
            ps.setBigDecimal(9, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw task.abend(abend);
        }
    }

    private static BigDecimal zoned(byte[] comm, FieldSpec f) {
        return ZonedDecimal.decode(comm, f.offset(), f.length(), f.scale(), ZonedDecimal.Sign.TRAILING);
    }

    private static String text(byte[] b, FieldSpec f) {
        return Text.decode(b, f.offset(), f.length(), Ebcdic.CP037);
    }

    private static void success(byte[] comm, char c) {
        Text.encode(String.valueOf(c), comm, Xfrfun.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    private static void fail(byte[] comm, char c) {
        Text.encode(String.valueOf(c), comm, Xfrfun.COMM_FAIL_CODE.offset(), 1, Ebcdic.CP037);
    }
}
