package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Delacc;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * DELACC: reads the account into the 122-byte DELACC COMMAREA, deletes it and writes an 'ODA' PROCTRAN row.
 * DEL-FAIL-CD 1 = not found, 3 = DELETE failed. A SELECT error abends HRAC and a PROCTRAN error abends HWPT,
 * so CICS backs out the DELETE with the task. The ABNDPROC diagnostic LINK before each abend is not ported.
 */
public final class DeleteAccount {
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private DeleteAccount() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        String accno = UpdateAccount.text(comm, Delacc.DELACC_ACCNO);
        String[] t = new String[5];
        BigDecimal rate;
        BigDecimal avail;
        BigDecimal actual;
        Date opened;
        Date last;
        Date next;
        int overdraft;
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER,"
                + " ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,"
                + " ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,"
                + " ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                + " WHERE ACCOUNT_NUMBER = ? AND ACCOUNT_SORTCODE = ?")) {
            ps.setString(1, accno);
            ps.setString(2, UpdateAccount.SORTCODE);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    Text.encode("N1", comm, Delacc.DELACC_DEL_SUCCESS.offset(), 2, Ebcdic.CP037);
                    return;
                }
                for (int i = 0; i < 5; i++) {
                    t[i] = rs.getString(i + 1);
                }
                rate = rs.getBigDecimal(6);
                opened = rs.getDate(7);
                overdraft = rs.getInt(8);
                boolean nullOverdraft = rs.wasNull();
                last = rs.getDate(9);
                next = rs.getDate(10);
                avail = rs.getBigDecimal(11);
                actual = rs.getBigDecimal(12);
                if (nullOverdraft || rate == null || opened == null || last == null || next == null
                        || avail == null || actual == null || java.util.Arrays.asList(t).contains(null)) {
                    throw task.abend("HRAC"); // SQLCODE -305: no indicator variables
                }
            }
        } catch (SQLException e) {
            throw task.abend("HRAC");
        }

        Text.encode(" ", comm, Delacc.DELACC_SUCCESS.offset(), 1, Ebcdic.CP037);
        Text.encode("Y ", comm, Delacc.DELACC_DEL_SUCCESS.offset(), 2, Ebcdic.CP037);
        Text.encode(t[0], comm, Delacc.DELACC_EYE.offset(), 4, Ebcdic.CP037);
        Text.encode(t[1], comm, Delacc.DELACC_CUSTNO.offset(), 10, Ebcdic.CP037);
        Text.encode(t[2], comm, Delacc.DELACC_SCODE.offset(), 6, Ebcdic.CP037);
        Text.encode(t[3], comm, Delacc.DELACC_ACCNO.offset(), 8, Ebcdic.CP037);
        Text.encode(t[4], comm, Delacc.DELACC_ACC_TYPE.offset(), 8, Ebcdic.CP037);
        // Through ACCOUNT-INTEREST-RATE PIC 9(4)V99: a negative stored rate loses its sign.
        UpdateAccount.put(comm, Delacc.DELACC_INT_RATE, rate);
        UpdateAccount.date(comm, Delacc.DELACC_OPENED_DAY, opened);
        UpdateAccount.put(comm, Delacc.DELACC_OVERDRAFT, BigDecimal.valueOf(overdraft));
        UpdateAccount.date(comm, Delacc.DELACC_LAST_STMT_DAY, last);
        UpdateAccount.date(comm, Delacc.DELACC_NEXT_STMT_DAY, next);
        UpdateAccount.put(comm, Delacc.DELACC_AVAIL_BAL, avail);
        UpdateAccount.put(comm, Delacc.DELACC_ACTUAL_BAL, actual);

        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM ACCOUNT WHERE ACCOUNT_SORTCODE = ? AND ACCOUNT_NUMBER = ?")) {
            ps.setString(1, t[2]);
            ps.setString(2, t[3]);
            if (ps.executeUpdate() == 0) {
                Text.encode("N3", comm, Delacc.DELACC_DEL_SUCCESS.offset(), 2, Ebcdic.CP037);
                return;
            }
        } catch (SQLException e) {
            Text.encode("N3", comm, Delacc.DELACC_DEL_SUCCESS.offset(), 2, Ebcdic.CP037);
            return;
        }

        LocalDateTime now = task.now();
        String desc = UpdateAccount.text(comm, Delacc.DELACC_CUSTNO)
                + UpdateAccount.text(comm, Delacc.DELACC_ACC_TYPE)
                + UpdateAccount.text(comm, Delacc.DELACC_LAST_STMT_DT)
                + UpdateAccount.text(comm, Delacc.DELACC_NEXT_STMT_DT) + "DELETE";
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, UpdateAccount.text(comm, Delacc.DELACC_SCODE));
            ps.setString(3, UpdateAccount.text(comm, Delacc.DELACC_ACCNO));
            // The COBOL binds FORMATTIME DDMMYYYY DATESEP('.') ('DD.MM.YYYY'); Db2 reads it as the same DATE.
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, "ODA");
            ps.setString(8, desc);
            ps.setBigDecimal(9, actual);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw task.abend("HWPT");
        }
    }
}
