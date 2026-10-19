package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Paydbcr;
import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DBCRFUN over the 132-byte PAYDBCR COMMAREA. FACILTYPE 496 marks a payment (z/OS Connect) request: payments may
 * not touch MORTGAGE/LOAN accounts (fail 4) or overdraw (fail 3); counter requests may overdraw without limit.
 * Balances are S9(10)V99 COMP-3 in Db2, so the new balances are truncated to 10 integer digits as COMPUTE does.
 * A failed PROCTRAN insert issues SYNCPOINT ROLLBACK and, as in the COBOL, MOVE '02' to the one-byte fail code
 * leaves '0'. The CICS HANDLE ABEND for AFCR/AFCS/AFCT is not reproduced: those are file-control abends.
 */
public final class DebitCredit {
    static final int PAYMENT = 496;
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private DebitCredit() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        status(comm, "N0");
        Text.encode(UpdateAccount.SORTCODE, comm, Paydbcr.COMM_SORTC.offset(), 6, Ebcdic.CP037);
        String accno = Text.decode(comm, Paydbcr.COMM_ACCNO.offset(), 8, Ebcdic.CP037);
        String type;
        BigDecimal avail;
        BigDecimal actual;
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_TYPE, ACCOUNT_AVAILABLE_BALANCE,"
                + " ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")) {
            ps.setString(1, UpdateAccount.SORTCODE);
            ps.setString(2, accno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    status(comm, "N1");
                    return;
                }
                type = rs.getString(1);
                avail = rs.getBigDecimal(2);
                actual = rs.getBigDecimal(3);
                if (type == null || avail == null || actual == null) {
                    status(comm, "N2");
                    return;
                }
            }
        } catch (SQLException e) {
            status(comm, "N2");
            return;
        }
        BigDecimal amt = zoned(comm, Paydbcr.COMM_AMT);
        boolean payment = ByteBuffer.wrap(comm).getInt(Paydbcr.COMM_FACILTYPE.offset()) == PAYMENT;
        String t = type.stripTrailing();
        if (payment && (t.equals("MORTGAGE") || t.equals("LOAN"))) {
            status(comm, "N4");
            return;
        }
        if (payment && amt.signum() < 0 && avail.add(amt).signum() < 0) {
            status(comm, "N3");
            return;
        }
        avail = Assign.fit(avail.add(amt), 12, 2, true, false);
        actual = Assign.fit(actual.add(amt), 12, 2, true, false);
        boolean updated;
        try (PreparedStatement ps = con.prepareStatement("UPDATE ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = ?,"
                + " ACCOUNT_ACTUAL_BALANCE = ? WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")) {
            ps.setBigDecimal(1, avail);
            ps.setBigDecimal(2, actual);
            ps.setString(3, UpdateAccount.SORTCODE);
            ps.setString(4, accno);
            updated = ps.executeUpdate() == 1;
        } catch (SQLException e) {
            updated = false;
        }
        // COMM-AV-BAL/COMM-ACT-BAL are set before the UPDATE's SQLCODE is checked.
        ZonedDecimal.encode(avail, comm, Paydbcr.COMM_AV_BAL.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        ZonedDecimal.encode(actual, comm, Paydbcr.COMM_ACT_BAL.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        if (!updated) {
            status(comm, "N2");
            return;
        }
        String desc = amt.signum() < 0 ? "COUNTER WTHDRW" : "COUNTER RECVED";
        String kind = amt.signum() < 0 ? (payment ? "PDR" : "DEB") : (payment ? "PCR" : "CRE");
        if (payment) {
            desc = Text.decode(comm, Paydbcr.COMM_DESCRIPTION.offset(), 40, Ebcdic.CP037);
            if (desc.isBlank()) {
                desc = Text.decode(comm, Paydbcr.COMM_APPLID.offset(), 14, Ebcdic.CP037);
            }
        }
        LocalDateTime now = task.now();
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, UpdateAccount.SORTCODE);
            ps.setString(3, accno);
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, kind);
            ps.setString(8, desc);
            ps.setBigDecimal(9, amt);
            ps.executeUpdate();
        } catch (SQLException e) {
            task.rollback();
            status(comm, "N0");
            return;
        }
        status(comm, "Y0");
    }

    private static BigDecimal zoned(byte[] comm, FieldSpec f) {
        return ZonedDecimal.decode(comm, f.offset(), f.length(), f.scale(), ZonedDecimal.Sign.TRAILING);
    }

    private static void status(byte[] comm, String s) {
        Text.encode(s, comm, Paydbcr.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
    }
}
