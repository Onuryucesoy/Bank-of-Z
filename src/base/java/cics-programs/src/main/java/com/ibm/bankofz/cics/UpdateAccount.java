package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Updacc;
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

/**
 * UPDACC: updates ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE and ACCOUNT_OVERDRAFT_LIMIT and returns the row in the
 * 99-byte UPDACC COMMAREA (CP037). Like the COBOL it never commits or rolls back: the work belongs to the
 * caller's unit of work and is committed at its syncpoint or task end.
 */
public final class UpdateAccount {
    static final String SORTCODE = "987654";

    private UpdateAccount() {
    }

    public static void run(byte[] comm, Connection con) throws SQLException {
        Text.encode(SORTCODE, comm, Updacc.COMM_SCODE.offset(), 6, Ebcdic.CP037);
        String accno = text(comm, Updacc.COMM_ACCNO);
        String eye;
        String custno;
        String sortcode;
        String accnoDb;
        Date opened;
        Date last;
        Date next;
        BigDecimal avail;
        BigDecimal actual;
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER,"
                + " ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,"
                + " ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE,"
                + " ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)")) {
            ps.setString(1, SORTCODE);
            ps.setString(2, accno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail(comm);
                    return;
                }
                eye = rs.getString(1);
                custno = rs.getString(2);
                sortcode = rs.getString(3);
                accnoDb = rs.getString(4);
                opened = rs.getDate(6);
                last = rs.getDate(7);
                next = rs.getDate(8);
                avail = rs.getBigDecimal(9);
                actual = rs.getBigDecimal(10);
                // No indicator variables in the COBOL: a NULL column is SQLCODE -305.
                if (eye == null || custno == null || opened == null || last == null
                        || next == null || avail == null || actual == null) {
                    fail(comm);
                    return;
                }
            }
        } catch (SQLException e) {
            fail(comm);
            return;
        }

        String type = text(comm, Updacc.COMM_ACC_TYPE);
        if (type.isBlank() || type.charAt(0) == ' ') {
            fail(comm);
            return;
        }
        BigDecimal newRate = number(comm, Updacc.COMM_INT_RATE);
        BigDecimal overdraft = number(comm, Updacc.COMM_OVERDRAFT);
        try (PreparedStatement ps = con.prepareStatement("UPDATE ACCOUNT SET ACCOUNT_TYPE = ?,"
                + " ACCOUNT_INTEREST_RATE = ?, ACCOUNT_OVERDRAFT_LIMIT = ?"
                + " WHERE (ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?)")) {
            ps.setString(1, type);
            ps.setBigDecimal(2, newRate);
            ps.setInt(3, overdraft.intValueExact());
            ps.setString(4, SORTCODE);
            ps.setString(5, accno);
            if (ps.executeUpdate() == 0) {
                fail(comm);
                return;
            }
        } catch (SQLException e) {
            fail(comm);
            return;
        }

        Text.encode(eye, comm, Updacc.COMM_EYE.offset(), 4, Ebcdic.CP037);
        Text.encode(custno, comm, Updacc.COMM_CUSTNO.offset(), 10, Ebcdic.CP037);
        Text.encode(sortcode, comm, Updacc.COMM_SCODE.offset(), 6, Ebcdic.CP037);
        Text.encode(accnoDb, comm, Updacc.COMM_ACCNO.offset(), 8, Ebcdic.CP037);
        Text.encode(type, comm, Updacc.COMM_ACC_TYPE.offset(), 8, Ebcdic.CP037);
        // HV-ACCOUNT-INT-RATE already holds COMM-INT-RATE, so the caller gets its own rate back.
        put(comm, Updacc.COMM_INT_RATE, newRate);
        date(comm, Updacc.COMM_OPENED_DAY, opened);
        put(comm, Updacc.COMM_OVERDRAFT, overdraft);
        date(comm, Updacc.COMM_LASTST_DAY, last);
        date(comm, Updacc.COMM_NEXTST_DAY, next);
        put(comm, Updacc.COMM_AVAIL_BAL, avail);
        put(comm, Updacc.COMM_ACTUAL_BAL, actual);
        Text.encode("Y", comm, Updacc.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    private static void fail(byte[] comm) {
        Text.encode("N", comm, Updacc.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    static String text(byte[] comm, FieldSpec f) {
        return Text.decode(comm, f.offset(), f.length(), Ebcdic.CP037);
    }

    private static BigDecimal number(byte[] comm, FieldSpec f) {
        return ZonedDecimal.decode(comm, f.offset(), f.digits(), f.scale(), sign(f));
    }

    static void put(byte[] comm, FieldSpec f, BigDecimal v) {
        ZonedDecimal.encode(Assign.fit(v, f.digits(), f.scale(), f.signed(), false), comm, f.offset(),
                f.digits(), f.scale(), sign(f));
    }

    private static ZonedDecimal.Sign sign(FieldSpec f) {
        return f.signed() ? ZonedDecimal.Sign.TRAILING : ZonedDecimal.Sign.NONE;
    }

    /** DB2-DATE-REFORMAT ('YYYY-MM-DD', ISO date format assumed) into a DD MM YYYY group starting at {@code day}. */
    static void date(byte[] comm, FieldSpec day, Date d) {
        String iso = d.toLocalDate().toString();
        String ddmmyyyy = iso.substring(8, 10) + iso.substring(5, 7) + iso.substring(0, 4);
        Text.encode(ddmmyyyy, comm, day.offset(), 8, Ebcdic.CP037);
    }
}
