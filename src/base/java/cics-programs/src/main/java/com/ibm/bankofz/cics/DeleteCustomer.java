package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Delacc;
import com.ibm.bankofz.bridge.copybook.cics.Delcus;
import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
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
 * DELCUS over the 399-byte DELCUS COMMAREA: checks the customer via INQCUST, LINKs DELACC for every account
 * INQACCCU returns (without looking at DELACC's result, as in the COBOL), then deletes the CUSTOMER row selected
 * by the caller's sort code and number and writes an 'ODC' PROCTRAN row. SQL errors abend WPV6 (select),
 * WPV7 (delete) or HWPT (PROCTRAN); a customer row that has vanished since INQCUST still reports success.
 */
public final class DeleteCustomer {
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");
    private static final FieldSpec[] TEXT = {Delcus.COMM_EYE, Delcus.COMM_SCODE, Delcus.COMM_CUSTNO,
        Delcus.COMM_TITLE, Delcus.COMM_FIRST_NAME, Delcus.COMM_LAST_NAME, null, Delcus.COMM_PHONE,
        Delcus.COMM_ADDR_LINE1, Delcus.COMM_ADDR_LINE2, Delcus.COMM_CITY, Delcus.COMM_POSTCODE, Delcus.COMM_COUNTRY,
        Delcus.COMM_STATUS};

    private DeleteCustomer() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        String custno = text(comm, Delcus.COMM_CUSTNO);
        byte[] inq = new byte[Inqcustz.LENGTH];
        Text.encode("", inq, 0, inq.length, Ebcdic.CP037);
        for (FieldSpec f : InquireAccountsForCustomer.INQCUST_NUMERIC) {
            ZonedDecimal.encode(BigDecimal.ZERO, inq, f.offset(), f.length(), 0, ZonedDecimal.Sign.NONE);
        }
        Text.encode(custno, inq, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037);
        task.link("INQCUST", inq);
        if ("N".equals(text(inq, Inqcustz.INQCUST_INQ_SUCCESS))) {
            status(comm, "N" + text(inq, Inqcustz.INQCUST_INQ_FAIL_CD));
            return;
        }
        byte[] accounts = new byte[Inqacccu.LENGTH];
        Text.encode("", accounts, 0, accounts.length, Ebcdic.CP037);
        ByteBuffer.wrap(accounts).putInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset(), 20);
        Text.encode(custno, accounts, Inqacccu.CUSTOMER_NUMBER.offset(), 10, Ebcdic.CP037);
        task.link("INQACCCU", accounts);
        int n = ByteBuffer.wrap(accounts).getInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset());
        for (int i = 0; i < n; i++) {
            byte[] del = new byte[Delacc.LENGTH];
            Text.encode("", del, 0, del.length, Ebcdic.CP037);
            System.arraycopy(accounts, Inqacccu.COMM_ACCNO.offset() + i * 98, del, Delacc.DELACC_ACCNO.offset(), 8);
            task.link("DELACC", del);
        }
        deleteCustomer(comm, con, task);
        status(comm, "Y ");
    }

    private static void deleteCustomer(byte[] comm, Connection con, CicsTask task) {
        String scode = text(comm, Delcus.COMM_SCODE);
        String custno = text(comm, Delcus.COMM_CUSTNO);
        String[] row = new String[17];
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT * FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")) {
            ps.setString(1, scode);
            ps.setString(2, custno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return;
                }
                for (int i = 0; i < row.length; i++) {
                    row[i] = rs.getString(i + 1);
                    if (row[i] == null) {
                        throw new SQLException("SQLCODE -305", "22002", -305);
                    }
                }
            }
        } catch (SQLException e) {
            throw task.abend("WPV6");
        }
        for (int i = 0; i < TEXT.length; i++) {
            if (TEXT[i] != null) {
                Text.encode(row[i], comm, TEXT[i].offset(), TEXT[i].length(), Ebcdic.CP037);
            }
        }
        String dob = date(comm, Delcus.COMM_DOB_DAY.offset(), Integer.parseInt(row[6].trim()));
        date(comm, Delcus.COMM_CREATED_DAY.offset(), Integer.parseInt(row[14].trim()));
        Text.encode(String.format("%03d", Integer.parseInt(row[15].trim()) % 1000), comm,
                Delcus.COMM_CREDIT_SCORE.offset(), 3, Ebcdic.CP037);
        date(comm, Delcus.COMM_CS_REVIEW_DAY.offset(), Integer.parseInt(row[16].trim()));
        try (PreparedStatement ps = con.prepareStatement(
                "DELETE FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")) {
            ps.setString(1, scode);
            ps.setString(2, custno);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("SQLCODE +100", "02000", 100);
            }
        } catch (SQLException e) {
            throw task.abend("WPV7");
        }
        String name = (CreateCustomer.upToDoubleSpace(String.format("%-50s", row[4])) + " "
                + CreateCustomer.upToDoubleSpace(String.format("%-50s", row[5])) + " ".repeat(14)).substring(0, 14);
        LocalDateTime now = task.now();
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, row[1]);
            ps.setString(3, "00000000");
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, "ODC");
            // Same as CRECUST: DD/MM/YYYY although PROC-TRAN-DESC-DELCUS declares YYYY-MM-DD.
            ps.setString(8, row[1] + row[2] + name + dob);
            ps.setBigDecimal(9, BigDecimal.ZERO.setScale(2));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw task.abend("HWPT");
        }
    }

    /** Writes YYYYMMDD as the DD/MM/YYYY zoned group at {@code at} and returns it as DD/MM/YYYY. */
    private static String date(byte[] comm, int at, int yyyymmdd) {
        String d = String.format("%02d", yyyymmdd % 100);
        String m = String.format("%02d", yyyymmdd / 100 % 100);
        String y = String.format("%04d", yyyymmdd / 10000 % 10000);
        Text.encode(d + m + y, comm, at, 8, Ebcdic.CP037);
        return d + "/" + m + "/" + y;
    }

    private static String text(byte[] b, FieldSpec f) {
        return Text.decode(b, f.offset(), f.length(), Ebcdic.CP037);
    }

    private static void status(byte[] comm, String s) {
        Text.encode(s, comm, Delcus.COMM_DEL_SUCCESS.offset(), 2, Ebcdic.CP037);
    }
}
