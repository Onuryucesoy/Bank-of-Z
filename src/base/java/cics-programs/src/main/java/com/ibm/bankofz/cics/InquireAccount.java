package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqacc;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * INQACC over the 103-byte INQACC COMMAREA. Account number 99999999 returns the highest-numbered account.
 * When the account is not found (or its stored type is blank) the COBOL still sets INQACC-SUCCESS to 'Y'
 * and leaves the rest of the COMMAREA untouched; that is kept. SQL errors abend HRAC, and an empty table
 * on the 99999999 path abends HNCS. The VSAM RLS abend handler (AFCR/AFCS/AFCT) has no counterpart.
 */
public final class InquireAccount {
    private static final String SELECT = "SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE,"
            + " ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT,"
            + " ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT, ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE"
            + " FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ?";

    private InquireAccount() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        String accno = UpdateAccount.text(comm, Inqacc.INQACC_ACCNO);
        boolean last = accno.equals("99999999");
        String abend = last ? "HNCS" : "HRAC";
        Object[] row;
        try (PreparedStatement ps = con.prepareStatement(last
                ? SELECT + " ORDER BY ACCOUNT_NUMBER DESC FETCH FIRST 1 ROWS ONLY"
                : SELECT + " AND ACCOUNT_NUMBER = ? FOR FETCH ONLY")) {
            ps.setString(1, UpdateAccount.SORTCODE);
            if (!last) {
                ps.setString(2, accno);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    if (last) {
                        throw task.abend(abend); // SQLCODE +100 is "not zero" in GLAD010
                    }
                    row = null;
                } else {
                    row = new Object[12];
                    for (int i = 0; i < row.length; i++) {
                        row[i] = rs.getObject(i + 1);
                        if (row[i] == null) {
                            throw task.abend(abend); // SQLCODE -305: no indicator variables
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw task.abend(abend);
        }
        if (row != null && !blank((String) row[4])) {
            String[] text = {(String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]};
            int[][] at = {{Inqacc.INQACC_EYE.offset(), 4}, {Inqacc.INQACC_CUSTNO.offset(), 10},
                {Inqacc.INQACC_SCODE.offset(), 6}, {Inqacc.INQACC_ACCNO.offset(), 8},
                {Inqacc.INQACC_ACC_TYPE.offset(), 8}};
            for (int i = 0; i < text.length; i++) {
                Text.encode(text[i], comm, at[i][0], at[i][1], Ebcdic.CP037);
            }
            UpdateAccount.put(comm, Inqacc.INQACC_INT_RATE, (BigDecimal) row[5]);
            UpdateAccount.date(comm, Inqacc.INQACC_OPENED_DAY, (Date) row[6]);
            UpdateAccount.put(comm, Inqacc.INQACC_OVERDRAFT, BigDecimal.valueOf(((Number) row[7]).longValue()));
            UpdateAccount.date(comm, Inqacc.INQACC_LAST_STMT_DAY, (Date) row[8]);
            UpdateAccount.date(comm, Inqacc.INQACC_NEXT_STMT_DAY, (Date) row[9]);
            UpdateAccount.put(comm, Inqacc.INQACC_AVAIL_BAL, (BigDecimal) row[10]);
            UpdateAccount.put(comm, Inqacc.INQACC_ACTUAL_BAL, (BigDecimal) row[11]);
        }
        Text.encode("Y", comm, Inqacc.INQACC_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    /** ACCOUNT-TYPE = SPACES OR LOW-VALUES. */
    private static boolean blank(String s) {
        return s.chars().allMatch(c -> c == ' ') || s.chars().allMatch(c -> c == 0);
    }
}
