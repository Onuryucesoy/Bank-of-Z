package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Delacc;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteAccountTest {
    Connection con;

    static final class Abend extends RuntimeException {
        final String code;

        Abend(String code) {
            super(code);
            this.code = code;
        }
    }

    static final CicsTask TASK = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return LocalDateTime.of(2024, 5, 6, 7, 8, 9); }
        @Override public RuntimeException abend(String code) { return new Abend(code); }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:delacc" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            for (String table : new String[] {"ACCOUNT", "PROCTRAN"}) {
                Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(" + table + ") \\((.*?)\\n\\s*\\)", Pattern.DOTALL)
                        .matcher(ddl);
                assertTrue(m.find());
                st.execute("CREATE TABLE " + table + " (" + m.group(2) + ")");
            }
            st.execute("INSERT INTO ACCOUNT VALUES ('ACCT', '0000000042', '987654', '00000007', 'ISA     ',"
                    + " -1.50, DATE '2021-03-04', 100, DATE '2024-01-31', DATE '2024-02-29', -123.45, 9876543210.99)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String accno) {
        byte[] c = new byte[Delacc.LENGTH];
        Text.encode("", c, 0, 110, Ebcdic.CP037);
        Text.encode(accno, c, Delacc.DELACC_ACCNO.offset(), 8, Ebcdic.CP037);
        return c;
    }

    int count(String table) throws Exception {
        try (ResultSet rs = con.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void deletesReturnsTheRowAndWritesProctran() throws Exception {
        byte[] c = comm("00000007");
        DeleteAccount.run(c, con, TASK);
        assertEquals("ACCT000000004298765400000007ISA     000150040320210000010031012024"
                + "29022024", Text.decode(c, 0, 74, Ebcdic.CP037));
        assertEquals(new BigDecimal("-123.45"), ZonedDecimal.decode(c, 74, 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals(new BigDecimal("9876543210.99"), ZonedDecimal.decode(c, 86, 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals("  Y ", Text.decode(c, 98, 4, Ebcdic.CP037));
        assertEquals(0, count("ACCOUNT"));
        try (ResultSet rs = con.createStatement().executeQuery("SELECT * FROM PROCTRAN")) {
            assertTrue(rs.next());
            assertEquals("PRTR|987654|00000007|2024-05-06|070809|000000004711|ODA", String.join("|",
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getDate(4).toString(), rs.getString(5),
                    rs.getString(6), rs.getString(7)));
            assertEquals("0000000042ISA     3101202429022024DELETE", rs.getString(8));
            assertEquals(new BigDecimal("9876543210.99"), rs.getBigDecimal(9));
            assertFalse(rs.next());
        }
    }

    @Test
    void notFoundSetsFailCode1Only() throws Exception {
        byte[] c = comm("00000099");
        byte[] expected = c.clone();
        Text.encode("N1", expected, 100, 2, Ebcdic.CP037);
        DeleteAccount.run(c, con, TASK);
        assertArrayEquals(expected, c);
        assertEquals(0, count("PROCTRAN"));
    }

    @Test
    void proctranFailureAbendsHwptAndTheDeleteIsBackedOut() throws Exception {
        con.createStatement().execute("DROP TABLE PROCTRAN");
        con.commit();
        Abend a = assertThrows(Abend.class, () -> DeleteAccount.run(comm("00000007"), con, TASK));
        assertEquals("HWPT", a.code);
        con.rollback();
        assertEquals(1, count("ACCOUNT"));
    }

    @Test
    void selectErrorAbendsHrac() throws Exception {
        con.createStatement().execute("UPDATE ACCOUNT SET ACCOUNT_TYPE = NULL");
        Abend a = assertThrows(Abend.class, () -> DeleteAccount.run(comm("00000007"), con, TASK));
        assertEquals("HRAC", a.code);
    }

    @Test
    void neverCommits() throws Exception {
        DeleteAccount.run(comm("00000007"), con, TASK);
        con.rollback();
        assertEquals(1, count("ACCOUNT"));
        assertEquals(0, count("PROCTRAN"));
    }
}
