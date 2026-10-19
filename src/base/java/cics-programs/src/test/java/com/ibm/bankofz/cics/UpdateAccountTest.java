package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Updacc;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateAccountTest {
    static final Path REPO = Path.of(System.getProperty("user.dir")).resolve("../../../..").normalize();
    Connection con;

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:updacc" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(ACCOUNT) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        assertTrue(m.find());
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            st.execute("CREATE TABLE ACCOUNT (" + m.group(2) + ")");
            st.execute("INSERT INTO ACCOUNT VALUES ('ACCT', '0000000042', '987654', '00000007', 'ISA     ',"
                    + " -1.50, DATE '2021-03-04', 100, DATE '2024-01-31', DATE '2024-02-29', -123.45, 9876543210.99)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String accno, String type, String rate, String overdraft) {
        byte[] c = new byte[Updacc.LENGTH];
        Text.encode("", c, 0, Updacc.LENGTH, Ebcdic.CP037);
        Text.encode(accno, c, Updacc.COMM_ACCNO.offset(), 8, Ebcdic.CP037);
        Text.encode(type, c, Updacc.COMM_ACC_TYPE.offset(), 8, Ebcdic.CP037);
        ZonedDecimal.encode(new BigDecimal(rate), c, Updacc.COMM_INT_RATE.offset(), 6, 2, ZonedDecimal.Sign.NONE);
        ZonedDecimal.encode(new BigDecimal(overdraft), c, Updacc.COMM_OVERDRAFT.offset(), 8, 0, ZonedDecimal.Sign.NONE);
        return c;
    }

    static String text(byte[] c, int off, int len) {
        return Text.decode(c, off, len, Ebcdic.CP037);
    }

    @Test
    void updatesAndReturnsTheRowByteExact() throws Exception {
        byte[] c = comm("00000007", "CURRENT", "2.25", "500");
        UpdateAccount.run(c, con);
        assertEquals("ACCT000000004298765400000007CURRENT 000225040320210000050031012024290220240000000123", text(c, 0, 84));
        assertEquals(new BigDecimal("-123.45"), ZonedDecimal.decode(c, Updacc.COMM_AVAIL_BAL.offset(), 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals(new BigDecimal("9876543210.99"), ZonedDecimal.decode(c, Updacc.COMM_ACTUAL_BAL.offset(), 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals((byte) 0xD5, c[Updacc.COMM_AVAIL_BAL.offset() + 11], "negative overpunch 5 -> X'D5'");
        assertEquals("Y", text(c, 98, 1));
        try (ResultSet rs = con.createStatement().executeQuery(
                "SELECT ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OVERDRAFT_LIMIT FROM ACCOUNT")) {
            rs.next();
            assertEquals("CURRENT", rs.getString(1).strip());
            assertEquals(new BigDecimal("2.25"), rs.getBigDecimal(2));
            assertEquals(500, rs.getInt(3));
        }
    }

    @Test
    void notFoundLeavesInputButSetsSortcodeAndN() throws Exception {
        byte[] c = comm("00000099", "CURRENT", "2.25", "500");
        byte[] expected = c.clone();
        Text.encode("987654", expected, Updacc.COMM_SCODE.offset(), 6, Ebcdic.CP037);
        Text.encode("N", expected, 98, 1, Ebcdic.CP037);
        UpdateAccount.run(c, con);
        assertArrayEquals(expected, c);
    }

    @Test
    void blankOrLeadingSpaceTypeIsRejectedWithoutUpdate() throws Exception {
        for (String t : new String[] {"", " ISA"}) {
            byte[] c = comm("00000007", t, "9.99", "1");
            UpdateAccount.run(c, con);
            assertEquals("N", text(c, 98, 1));
        }
        try (ResultSet rs = con.createStatement().executeQuery("SELECT ACCOUNT_OVERDRAFT_LIMIT FROM ACCOUNT")) {
            rs.next();
            assertEquals(100, rs.getInt(1));
        }
    }

    @Test
    void neverCommitsOrRollsBack() throws Exception {
        UpdateAccount.run(comm("00000007", "CURRENT", "2.25", "500"), con);
        con.rollback();
        try (ResultSet rs = con.createStatement().executeQuery("SELECT ACCOUNT_OVERDRAFT_LIMIT FROM ACCOUNT")) {
            rs.next();
            assertEquals(100, rs.getInt(1), "the update must still belong to the caller's unit of work");
        }
    }
}
