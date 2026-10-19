package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Creacc;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * CREACC over the 100-byte CREACC COMMAREA. The customer is checked by LINKing INQCUST and its account count
 * by LINKing INQACCCU, so either the COBOL or the Java versions answer. Under ENQ BANKZACCT the CONTROL rows
 * 987654-ACCOUNT-LAST and 987654-ACCOUNT-COUNT are bumped (any SQL problem abends HNCS), then the ACCOUNT row and
 * an 'OCA' PROCTRAN row are inserted. Fail codes: 1 customer, 9 INQACCCU, 8 ten or more accounts, A type,
 * 3 ENQ, 7 insert, 5 DEQ; a PROCTRAN failure abends HWPT.
 */
public final class CreateAccount {
    static final String ENQ_NAME = "BANKZACCT" + UpdateAccount.SORTCODE + " ";
    static final List<String> TYPES = List.of("ISA", "MORTGAGE", "SAVING", "CURRENT", "LOAN");
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    private CreateAccount() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        String custno = text(comm, Creacc.COMM_CUSTNO);
        byte[] inq = new byte[Inqcustz.LENGTH];
        Text.encode("", inq, 0, inq.length, Ebcdic.CP037);
        for (FieldSpec f : InquireAccountsForCustomer.INQCUST_NUMERIC) {
            ZonedDecimal.encode(BigDecimal.ZERO, inq, f.offset(), f.length(), 0, ZonedDecimal.Sign.NONE);
        }
        Text.encode(custno, inq, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037);
        if (!task.tryLink("INQCUST", inq) || !"Y".equals(text(inq, Inqcustz.INQCUST_INQ_SUCCESS))) {
            status(comm, "N1");
            return;
        }
        byte[] accounts = new byte[Inqacccu.LENGTH];
        Text.encode("", accounts, 0, accounts.length, Ebcdic.CP037);
        ByteBuffer.wrap(accounts).putInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset(), 20);
        Text.encode(custno, accounts, Inqacccu.CUSTOMER_NUMBER.offset(), 10, Ebcdic.CP037);
        if (!task.tryLink("INQACCCU", accounts) || "N".equals(text(accounts, Inqacccu.COMM_SUCCESS))) {
            status(comm, "N9");
            return;
        }
        if (ByteBuffer.wrap(accounts).getInt(Inqacccu.NUMBER_OF_ACCOUNTS.offset()) > 9) {
            status(comm, "N8");
            return;
        }
        // ACCOUNT-TYPE-CHECK compares only the length of each name, so 'ISAXXXXX' passes.
        String type = text(comm, Creacc.COMM_ACC_TYPE);
        if (TYPES.stream().noneMatch(type::startsWith)) {
            status(comm, "NA");
            return;
        }
        Text.encode("Y", comm, Creacc.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
        if (!task.enq(ENQ_NAME)) {
            status(comm, "N3");
            return;
        }
        long next;
        try {
            next = bump(con, UpdateAccount.SORTCODE + "-ACCOUNT-LAST");
            bump(con, UpdateAccount.SORTCODE + "-ACCOUNT-COUNT");
        } catch (SQLException e) {
            throw task.abend("HNCS");
        }
        String accno = String.format("%016d", next).substring(8);
        Text.encode(accno, comm, Creacc.COMM_NUMBER.offset(), 8, Ebcdic.CP037);
        LocalDateTime now = task.now();
        LocalDate today = now.toLocalDate();
        LocalDate nextStatement = today.plusDays(30);
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO ACCOUNT (ACCOUNT_EYECATCHER,"
                + " ACCOUNT_CUSTOMER_NUMBER, ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE,"
                + " ACCOUNT_OPENED, ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,"
                + " ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "ACCT");
            ps.setString(2, custno);
            ps.setString(3, UpdateAccount.SORTCODE);
            ps.setString(4, accno);
            ps.setString(5, type);
            ps.setBigDecimal(6, number(comm, Creacc.COMM_INT_RT, ZonedDecimal.Sign.NONE));
            ps.setDate(7, Date.valueOf(today));
            ps.setInt(8, number(comm, Creacc.COMM_OVERDR_LIM, ZonedDecimal.Sign.NONE).intValueExact());
            ps.setDate(9, Date.valueOf(today));
            ps.setDate(10, Date.valueOf(nextStatement));
            ps.setBigDecimal(11, number(comm, Creacc.COMM_AVAIL_BAL, ZonedDecimal.Sign.TRAILING));
            ps.setBigDecimal(12, number(comm, Creacc.COMM_ACT_BAL, ZonedDecimal.Sign.TRAILING));
            ps.executeUpdate();
        } catch (SQLException e) {
            status(comm, "N7");
            task.deq(ENQ_NAME);
            return;
        }
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, UpdateAccount.SORTCODE);
            ps.setString(3, accno);
            ps.setDate(4, Date.valueOf(today));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, "OCA");
            // PROC-DESC-CREACC-FOOTER is declared VALUE 'CREATE' but the COBOL writes spaces there.
            ps.setString(8, custno + type + today.format(DDMMYYYY) + nextStatement.format(DDMMYYYY) + "      ");
            ps.setBigDecimal(9, BigDecimal.ZERO.setScale(2));
            ps.executeUpdate();
        } catch (SQLException e) {
            task.deq(ENQ_NAME);
            throw task.abend("HWPT");
        }
        if (!task.deq(ENQ_NAME)) {
            status(comm, "N5");
            return;
        }
        Text.encode(UpdateAccount.SORTCODE, comm, Creacc.COMM_SORTCODE.offset(), 6, Ebcdic.CP037);
        Text.encode(today.format(DDMMYYYY), comm, Creacc.COMM_OPENED.offset(), 8, Ebcdic.CP037);
        Text.encode(today.format(DDMMYYYY), comm, Creacc.COMM_LAST_STMT_DT.offset(), 8, Ebcdic.CP037);
        Text.encode(nextStatement.format(DDMMYYYY), comm, Creacc.COMM_NEXT_STMT_DT.offset(), 8, Ebcdic.CP037);
        Text.encode("ACCT", comm, Creacc.COMM_EYECATCHER.offset(), 4, Ebcdic.CP037);
        status(comm, "Y ");
    }

    private static long bump(Connection con, String name) throws SQLException {
        long value;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("SQLCODE +100", "02000", 100);
                }
                value = rs.getLong(1) + 1;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE CONTROL SET CONTROL_VALUE_NUM = ? WHERE CONTROL_NAME = ?")) {
            ps.setLong(1, value);
            ps.setString(2, name);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("SQLCODE +100", "02000", 100);
            }
        }
        return value;
    }

    private static BigDecimal number(byte[] comm, FieldSpec f, ZonedDecimal.Sign sign) {
        return ZonedDecimal.decode(comm, f.offset(), f.length(), f.scale(), sign);
    }

    private static String text(byte[] b, FieldSpec f) {
        return Text.decode(b, f.offset(), f.length(), Ebcdic.CP037);
    }

    private static void status(byte[] comm, String s) {
        Text.encode(s, comm, Creacc.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
    }
}
