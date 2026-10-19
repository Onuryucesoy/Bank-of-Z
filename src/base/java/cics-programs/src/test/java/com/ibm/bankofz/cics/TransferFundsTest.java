package com.ibm.bankofz.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.bridge.copybook.cics.Xfrfun;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TransferFundsTest {
    Connection con;
    int rollbacks;

    final CicsTask task = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return LocalDateTime.of(2024, 5, 6, 7, 8, 9); }
        @Override public RuntimeException abend(String code) { return new DeleteAccountTest.Abend(code); }
        @Override public void rollback() {
            rollbacks++;
            try {
                con.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:xfrfun" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            for (String table : new String[] {"ACCOUNT", "PROCTRAN"}) {
                Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(" + table + ") \\((.*?)\\n\\s*\\)", Pattern.DOTALL)
                        .matcher(ddl);
                assertTrue(m.find(), table);
                st.execute("CREATE TABLE " + table + " (" + m.group(2) + ")");
            }
            String row = "INSERT INTO ACCOUNT VALUES ('ACCT', '0000000042', '987654', '%s', 'ISA', 1.50, DATE '2021-03-04',"
                    + " 100, DATE '2024-01-31', DATE '2024-02-29', %s, %s)";
            st.execute(String.format(row, "00000007", "100.00", "200.00"));
            st.execute(String.format(row, "00000009", "50.00", "50.00"));
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String from, String to, String amt) {
        byte[] c = new byte[Xfrfun.LENGTH];
        Text.encode("", c, 0, c.length, Ebcdic.CP037);
        Text.encode(from, c, Xfrfun.COMM_FACCNO.offset(), 8, Ebcdic.CP037);
        Text.encode(to, c, Xfrfun.COMM_TACCNO.offset(), 8, Ebcdic.CP037);
        ZonedDecimal.encode(new BigDecimal(amt), c, Xfrfun.COMM_AMT.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        return c;
    }

    static String status(byte[] c) {
        return Text.decode(c, Xfrfun.COMM_FAIL_CODE.offset(), 2, Ebcdic.CP037);
    }

    static String balances(byte[] c) {
        StringBuilder out = new StringBuilder();
        for (int at = 40; at < 88; at += 12) {
            out.append(ZonedDecimal.decode(c, at, 12, 2, ZonedDecimal.Sign.TRAILING)).append(' ');
        }
        return out.toString().trim();
    }

    String query(String sql) throws Exception {
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = con.createStatement().executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= n; i++) {
                    out.append(i > 1 ? "|" : "").append(String.valueOf(rs.getObject(i)).trim());
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void transfersAndWritesTwoTfrRows() throws Exception {
        byte[] c = comm("00000007", "00000009", "25.50");
        TransferFunds.run(c, con, task);
        assertEquals(" Y", status(c));
        assertEquals("000000079876540000000998765400000000255{", Text.decode(c, 0, 40, Ebcdic.CP037));
        assertEquals("74.50 174.50 75.50 75.50", balances(c));
        assertEquals("00000007|TFR|TRANSFER                  98765400000009|-25.50|000000004711\n"
                + "00000009|TFR|TRANSFER                  98765400000007|25.50|000000004711\n",
                query("SELECT PROCTRAN_NUMBER, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT, PROCTRAN_REF FROM PROCTRAN"));
        con.rollback();
        assertEquals("", query("SELECT * FROM PROCTRAN"), "XFRFUN leaves the commit to the CICS task");
    }

    @Test
    void fromAccountMayOverdrawWhicheverOrderIsLocked() throws Exception {
        byte[] c = comm("00000009", "00000007", "1000.00");
        TransferFunds.run(c, con, task);
        assertEquals(" Y", status(c));
        assertEquals("-950.00 -950.00 1100.00 1200.00", balances(c));
    }

    @Test
    void rejectsNonPositiveAmountAndSameAccount() {
        byte[] c = comm("00000007", "00000009", "0.00");
        TransferFunds.run(c, con, task);
        assertEquals("4N", status(c));
        assertEquals("SAME", assertThrows(DeleteAccountTest.Abend.class,
                () -> TransferFunds.run(comm("00000007", "00000007", "1.00"), con, task)).code);
    }

    @Test
    void missingAccountsRollBack() throws Exception {
        byte[] c = comm("00000001", "00000007", "5.00");
        TransferFunds.run(c, con, task);
        assertEquals("1N", status(c));
        c = comm("00000007", "00000099", "5.00");
        TransferFunds.run(c, con, task);
        assertEquals("2N", status(c));
        c = comm("00000099", "00000007", "5.00");
        TransferFunds.run(c, con, task);
        assertEquals("1N", status(c), "TO is updated first here, then rolled back when FROM is missing");
        assertEquals(3, rollbacks);
        assertEquals("100.00|200.00\n50.00|50.00\n", query("SELECT ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE"
                + " FROM ACCOUNT ORDER BY ACCOUNT_NUMBER"));
    }

    @Test
    void proctranFailureAbendsWpcd() throws Exception {
        con.createStatement().execute("DROP TABLE PROCTRAN");
        assertEquals("WPCD", assertThrows(DeleteAccountTest.Abend.class,
                () -> TransferFunds.run(comm("00000007", "00000009", "1.00"), con, task)).code);
    }
}
