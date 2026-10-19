package com.ibm.bankofz.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.bridge.copybook.cics.Paydbcr;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
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

class DebitCreditTest {
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
        con = DriverManager.getConnection("jdbc:h2:mem:dbcrfun" + System.nanoTime() + ";MODE=DB2");
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
            String row = "INSERT INTO ACCOUNT VALUES ('ACCT', '0000000042', '987654', '%s', '%s', 1.50, DATE '2021-03-04',"
                    + " 100, DATE '2024-01-31', DATE '2024-02-29', %s, %s)";
            st.execute(String.format(row, "00000007", "ISA", "100.00", "200.00"));
            st.execute(String.format(row, "00000008", "LOAN", "100.00", "200.00"));
            st.execute(String.format(row, "00000009", "SAVING", "9999999999.99", "-9999999999.99"));
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String accno, String amt, int facility, String description) {
        byte[] c = new byte[Paydbcr.LENGTH];
        Text.encode("", c, 0, c.length, Ebcdic.CP037);
        Text.encode(accno, c, Paydbcr.COMM_ACCNO.offset(), 8, Ebcdic.CP037);
        ZonedDecimal.encode(new BigDecimal(amt), c, Paydbcr.COMM_AMT.offset(), 12, 2, ZonedDecimal.Sign.TRAILING);
        Text.encode("CICSAPPLBANKUSER", c, Paydbcr.COMM_APPLID.offset(), 16, Ebcdic.CP037);
        ByteBuffer.wrap(c).putInt(Paydbcr.COMM_FACILTYPE.offset(), facility);
        Text.encode(description, c, Paydbcr.COMM_DESCRIPTION.offset(), 40, Ebcdic.CP037);
        return c;
    }

    static String status(byte[] c) {
        return Text.decode(c, Paydbcr.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
    }

    static BigDecimal bal(byte[] c, int at) {
        return ZonedDecimal.decode(c, at, 12, 2, ZonedDecimal.Sign.TRAILING);
    }

    String proctran() throws Exception {
        StringBuilder out = new StringBuilder();
        try (ResultSet rs = con.createStatement().executeQuery(
                "SELECT PROCTRAN_NUMBER, PROCTRAN_TYPE, PROCTRAN_DESC, PROCTRAN_AMOUNT, PROCTRAN_REF FROM PROCTRAN")) {
            while (rs.next()) {
                out.append(rs.getString(1)).append('|').append(rs.getString(2)).append('|')
                        .append(rs.getString(3).trim()).append('|').append(rs.getBigDecimal(4)).append('|')
                        .append(rs.getString(5)).append('\n');
            }
        }
        return out.toString();
    }

    @Test
    void counterCreditAndUnlimitedCounterOverdraft() throws Exception {
        byte[] c = comm("00000007", "50.25", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("Y0", status(c));
        assertEquals("987654", Text.decode(c, Paydbcr.COMM_SORTC.offset(), 6, Ebcdic.CP037));
        assertEquals(new BigDecimal("150.25"), bal(c, 26));
        assertEquals(new BigDecimal("250.25"), bal(c, 38));
        c = comm("00000007", "-500.00", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("Y0", status(c));
        assertEquals(new BigDecimal("-349.75"), bal(c, 26));
        assertEquals("00000007|CRE|COUNTER RECVED|50.25|000000004711\n00000007|DEB|COUNTER WTHDRW|-500.00|000000004711\n",
                proctran());
        con.rollback();
        assertEquals("", proctran(), "DBCRFUN leaves the commit to the CICS task");
    }

    @Test
    void paymentsCannotOverdrawOrTouchLoans() throws Exception {
        byte[] c = comm("00000007", "-100.01", 496, "");
        DebitCredit.run(c, con, task);
        assertEquals("N3", status(c));
        for (String amt : new String[] {"-1.00", "1.00"}) {
            c = comm("00000008", amt, 496, "");
            DebitCredit.run(c, con, task);
            assertEquals("N4", status(c), amt);
        }
        assertEquals("", proctran());
        c = comm("00000008", "-1.00", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("Y0", status(c), "counter requests may debit a loan");
    }

    @Test
    void paymentDescriptionFallsBackToTheOrigin() throws Exception {
        DebitCredit.run(comm("00000007", "-100.00", 496, "RENT MAY"), con, task);
        DebitCredit.run(comm("00000007", "12.00", 496, ""), con, task);
        assertEquals("00000007|PDR|RENT MAY|-100.00|000000004711\n00000007|PCR|CICSAPPLBANKUS|12.00|000000004711\n",
                proctran());
    }

    @Test
    void balancesTruncateHighOrderDigitsLikeComp3() throws Exception {
        byte[] c = comm("00000009", "0.01", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("Y0", status(c));
        assertEquals(new BigDecimal("0.00"), bal(c, 26));
        assertEquals(new BigDecimal("-9999999999.98"), bal(c, 38));
    }

    @Test
    void failures() throws Exception {
        byte[] c = comm("00000099", "1.00", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("N1", status(c));
        con.createStatement().execute("DROP TABLE PROCTRAN");
        c = comm("00000007", "1.00", 0, "");
        DebitCredit.run(c, con, task);
        assertEquals("N0", status(c), "MOVE '02' to a PIC X fail code keeps only '0'");
        assertEquals(1, rollbacks);
        try (ResultSet rs = con.createStatement().executeQuery(
                "SELECT ACCOUNT_AVAILABLE_BALANCE FROM ACCOUNT WHERE ACCOUNT_NUMBER = '00000007'")) {
            rs.next();
            assertEquals(new BigDecimal("100.00"), rs.getBigDecimal(1));
        }
    }
}
