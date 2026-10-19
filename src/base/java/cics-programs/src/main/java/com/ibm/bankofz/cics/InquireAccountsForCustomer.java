package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
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
import java.time.format.DateTimeFormatter;

/**
 * INQACCCU over the 1981-byte INQACCCU COMMAREA (up to 20 98-byte ACCOUNT-DETAILS, ODO on NUMBER-OF-ACCOUNTS).
 * The customer is checked by LINKing to INQCUST, so it reaches whichever INQCUST (COBOL or Java) is installed.
 * Fail codes: 1 customer not found, 2 OPEN, 3 FETCH, 4 CLOSE; SQL failures also SYNCPOINT ROLLBACK the task.
 * The cursor has no ORDER BY, so account order is Db2's; parity diffs must compare entries as a set.
 */
public final class InquireAccountsForCustomer {
    static final int ENTRY = 98;
    static final int MAX = 20;
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final FieldSpec[] INQCUST_NUMERIC = {Inqcustz.INQCUST_CUSTNO, Inqcustz.INQCUST_DOB_DD,
        Inqcustz.INQCUST_DOB_MM, Inqcustz.INQCUST_DOB_YYYY, Inqcustz.INQCUST_CREATED_DD,
        Inqcustz.INQCUST_CREATED_MM, Inqcustz.INQCUST_CREATED_YYYY, Inqcustz.INQCUST_CREDIT_SCORE,
        Inqcustz.INQCUST_CS_REVIEW_DD, Inqcustz.INQCUST_CS_REVIEW_MM, Inqcustz.INQCUST_CS_REVIEW_YYYY};

    private InquireAccountsForCustomer() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        flags(comm, "N0", null);
        String custno = UpdateAccount.text(comm, Inqacccu.CUSTOMER_NUMBER);
        if (!customerExists(comm, custno, task)) {
            flags(comm, "N1", null);
            return;
        }
        int n = 0;
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER,"
                + " ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,"
                + " ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,"
                + " ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                + " WHERE ACCOUNT_CUSTOMER_NUMBER = ? AND ACCOUNT_SORTCODE = ? FOR FETCH ONLY")) {
            ps.setString(1, custno);
            ps.setString(2, UpdateAccount.SORTCODE);
            ResultSet rs;
            try {
                rs = ps.executeQuery();
            } catch (SQLException e) {
                fail(comm, "2", task);
                return;
            }
            try (rs) {
                while (n < MAX) {
                    Object[] row;
                    try {
                        if (!rs.next()) {
                            break;
                        }
                        row = new Object[12];
                        for (int i = 0; i < row.length; i++) {
                            row[i] = rs.getObject(i + 1);
                            if (row[i] == null) {
                                throw new SQLException("SQLCODE -305", "22002", -305);
                            }
                        }
                    } catch (SQLException e) {
                        fail(comm, "3", task);
                        return;
                    }
                    entry(comm, Inqacccu.ACCOUNT_DETAILS.offset() + ENTRY * n, row);
                    n++;
                }
            } catch (SQLException e) {
                fail(comm, "4", task);
                return;
            }
        } catch (SQLException e) {
            fail(comm, "2", task);
            return;
        }
        ByteBuffer.wrap(comm).putInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset(), n);
        Text.encode("Y", comm, Inqacccu.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    /** CUSTOMER-CHECK: 0 and 9999999999 are never valid here; anything else is asked of INQCUST. */
    private static boolean customerExists(byte[] comm, String custno, CicsTask task) {
        if (custno.equals("0000000000") || custno.equals("9999999999")) {
            flags(comm, null, "N");
            return false;
        }
        byte[] inq = new byte[Inqcustz.LENGTH];
        Text.encode("", inq, 0, inq.length, Ebcdic.CP037);
        for (FieldSpec f : INQCUST_NUMERIC) {
            ZonedDecimal.encode(BigDecimal.ZERO, inq, f.offset(), f.length(), 0, ZonedDecimal.Sign.NONE);
        }
        Text.encode(custno, inq, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037);
        task.link("INQCUST", inq);
        boolean found = "Y".equals(Text.decode(inq, Inqcustz.INQCUST_INQ_SUCCESS.offset(), 1, Ebcdic.CP037));
        flags(comm, null, found ? "Y" : "N");
        return found;
    }

    private static void fail(byte[] comm, String code, CicsTask task) {
        flags(comm, "N" + code, "N");
        task.rollback();
    }

    /** COMM-SUCCESS + COMM-FAIL-CODE and CUSTOMER-FOUND; a not-found customer also zeroes the count. */
    private static void flags(byte[] comm, String successAndCode, String found) {
        if (successAndCode != null) {
            Text.encode(successAndCode, comm, Inqacccu.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
        }
        if (found != null) {
            Text.encode(found, comm, Inqacccu.CUSTOMER_FOUND.offset(), 1, Ebcdic.CP037);
            if (found.equals("N")) {
                ByteBuffer.wrap(comm).putInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset(), 0);
            }
        }
    }

    private static void entry(byte[] comm, int at, Object[] row) {
        int[][] text = {{0, 4}, {4, 10}, {14, 6}, {20, 8}, {28, 8}};
        for (int i = 0; i < text.length; i++) {
            Text.encode((String) row[i], comm, at + text[i][0], text[i][1], Ebcdic.CP037);
        }
        zoned(comm, at + 36, 6, 2, false, (BigDecimal) row[5]);
        Text.encode(((Date) row[6]).toLocalDate().format(DDMMYYYY), comm, at + 42, 8, Ebcdic.CP037);
        zoned(comm, at + 50, 8, 0, false, BigDecimal.valueOf(((Number) row[7]).longValue()));
        Text.encode(((Date) row[8]).toLocalDate().format(DDMMYYYY), comm, at + 58, 8, Ebcdic.CP037);
        Text.encode(((Date) row[9]).toLocalDate().format(DDMMYYYY), comm, at + 66, 8, Ebcdic.CP037);
        zoned(comm, at + 74, 12, 2, true, (BigDecimal) row[10]);
        zoned(comm, at + 86, 12, 2, true, (BigDecimal) row[11]);
    }

    private static void zoned(byte[] comm, int off, int digits, int scale, boolean signed, BigDecimal v) {
        ZonedDecimal.encode(Assign.fit(v, digits, scale, signed, false), comm, off, digits, scale,
                signed ? ZonedDecimal.Sign.TRAILING : ZonedDecimal.Sign.NONE);
    }
}
