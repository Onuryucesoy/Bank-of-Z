package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqacc;
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
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InquireAccountTest {
    Connection con;

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:inqacc" + System.nanoTime() + ";MODE=DB2");
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(ACCOUNT) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        assertTrue(m.find());
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            st.execute("CREATE TABLE ACCOUNT (" + m.group(2) + ")");
            insert("00000007", "ISA     ", "-123.45");
            insert("00000009", "CURRENT ", "5.00");
        }
    }

    void insert(String accno, String type, String avail) throws Exception {
        con.createStatement().execute("INSERT INTO ACCOUNT VALUES ('ACCT', '0000000042', '987654', '" + accno
                + "', '" + type + "', 1.50, DATE '2021-03-04', 100, DATE '2024-01-31', DATE '2024-02-29', " + avail
                + ", 9876543210.99)");
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String accno) {
        byte[] c = new byte[Inqacc.LENGTH];
        Text.encode("", c, 0, 99, Ebcdic.CP037);
        Text.encode(accno, c, Inqacc.INQACC_ACCNO.offset(), 8, Ebcdic.CP037);
        return c;
    }

    @Test
    void returnsTheAccount() {
        byte[] c = comm("00000007");
        InquireAccount.run(c, con, DeleteAccountTest.TASK);
        assertEquals("ACCT000000004298765400000007ISA     000150040320210000010031012024"
                + "29022024", Text.decode(c, 0, 74, Ebcdic.CP037));
        assertEquals(new BigDecimal("-123.45"), ZonedDecimal.decode(c, 74, 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals(new BigDecimal("9876543210.99"), ZonedDecimal.decode(c, 86, 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals("Y", Text.decode(c, 98, 1, Ebcdic.CP037));
    }

    @Test
    void notFoundOrBlankTypeStillReportsSuccessAndLeavesTheCommareaAlone() throws Exception {
        insert("00000003", "        ", "1.00");
        for (String n : new String[] {"00000005", "00000003"}) {
            byte[] c = comm(n);
            byte[] expected = c.clone();
            Text.encode("Y", expected, 98, 1, Ebcdic.CP037);
            InquireAccount.run(c, con, DeleteAccountTest.TASK);
            assertArrayEquals(expected, c, n);
        }
    }

    @Test
    void accountNumber99999999ReturnsTheHighestAccount() {
        byte[] c = comm("99999999");
        InquireAccount.run(c, con, DeleteAccountTest.TASK);
        assertEquals("00000009CURRENT ", Text.decode(c, 20, 16, Ebcdic.CP037));
    }

    @Test
    void emptyTableOnTheLastAccountPathAbendsHncs() throws Exception {
        con.createStatement().execute("DELETE FROM ACCOUNT");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> InquireAccount.run(comm("99999999"), con, DeleteAccountTest.TASK));
        assertEquals("HNCS", a.code);
    }

    @Test
    void nullColumnAbendsHrac() throws Exception {
        con.createStatement().execute("UPDATE ACCOUNT SET ACCOUNT_OPENED = NULL");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> InquireAccount.run(comm("00000007"), con, DeleteAccountTest.TASK));
        assertEquals("HRAC", a.code);
    }
}
