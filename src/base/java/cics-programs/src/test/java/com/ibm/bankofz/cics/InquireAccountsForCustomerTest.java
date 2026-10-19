package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InquireAccountsForCustomerTest {
    Connection con;
    final List<byte[]> links = new ArrayList<>();
    int rollbacks;

    final CicsTask task = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return LocalDateTime.of(2024, 5, 6, 7, 8, 9); }
        @Override public RuntimeException abend(String code) { return new DeleteAccountTest.Abend(code); }

        @Override
        public void link(String program, byte[] commarea) {
            assertEquals("INQCUST", program);
            links.add(commarea.clone());
            boolean known = Text.decode(commarea, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037)
                    .equals("0000000042");
            Text.encode(known ? "Y" : "N", commarea, Inqcustz.INQCUST_INQ_SUCCESS.offset(), 1, Ebcdic.CP037);
        }

        @Override
        public void rollback() {
            rollbacks++;
        }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:inqacccu" + System.nanoTime() + ";MODE=DB2");
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(ACCOUNT) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        assertTrue(m.find());
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            st.execute("CREATE TABLE ACCOUNT (" + m.group(2) + ")");
        }
        insert("0000000042", 7, "-123.45");
        insert("0000000042", 9, "5.00");
        insert("0000000043", 8, "1.00");
    }

    void insert(String cust, int accno, String avail) throws Exception {
        con.createStatement().execute(String.format("INSERT INTO ACCOUNT VALUES ('ACCT', '%s', '987654', '%08d',"
                + " 'ISA     ', 1.50, DATE '2021-03-04', 100, DATE '2024-01-31', DATE '2024-02-29', %s,"
                + " 9876543210.99)", cust, accno, avail));
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String custno) {
        byte[] c = new byte[Inqacccu.LENGTH];
        Text.encode("", c, 4, c.length - 4, Ebcdic.CP037);
        Text.encode(custno, c, Inqacccu.CUSTOMER_NUMBER.offset(), 10, Ebcdic.CP037);
        return c;
    }

    static int count(byte[] c) {
        return ByteBuffer.wrap(c).getInt(0);
    }

    static String flags(byte[] c) {
        return Text.decode(c, 14, 3, Ebcdic.CP037);
    }

    @Test
    void returnsTheCustomersAccounts() {
        byte[] c = comm("0000000042");
        InquireAccountsForCustomer.run(c, con, task);
        assertEquals(2, count(c));
        assertEquals("Y0Y", flags(c));
        Set<String> entries = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            int at = 21 + 98 * i;
            entries.add(Text.decode(c, at, 74, Ebcdic.CP037));
            assertEquals(new BigDecimal("9876543210.99"),
                    ZonedDecimal.decode(c, at + 86, 12, 2, ZonedDecimal.Sign.TRAILING));
        }
        assertEquals(Set.of(
                "ACCT000000004298765400000007ISA     000150040320210000010031012024" + "29022024",
                "ACCT000000004298765400000009ISA     000150040320210000010031012024" + "29022024"), entries);
        assertEquals(" ", Text.decode(c, 21 + 98 * 2, 1, Ebcdic.CP037), "entries past the count are untouched");
        byte[] inq = links.get(0);
        assertEquals("0000000042", Text.decode(inq, 10, 10, Ebcdic.CP037));
        assertEquals("00000000", Text.decode(inq, Inqcustz.INQCUST_DOB.offset(), 8, Ebcdic.CP037), "INITIALIZE");
        assertEquals(" ", Text.decode(inq, Inqcustz.INQCUST_TITLE.offset(), 1, Ebcdic.CP037));
    }

    @Test
    void customerZeroOrLastIsNeverValidAndUnknownCustomersFailWithCode1() {
        for (String n : new String[] {"0000000000", "9999999999", "0000000044"}) {
            byte[] c = comm(n);
            ByteBuffer.wrap(c).putInt(0, 5);
            InquireAccountsForCustomer.run(c, con, task);
            assertEquals("N1N", flags(c), n);
            assertEquals(0, count(c), n);
        }
        assertEquals(1, links.size(), "only 0000000044 reaches INQCUST");
    }

    @Test
    void stopsAtTwentyAccounts() throws Exception {
        for (int i = 10; i < 30; i++) {
            insert("0000000042", i, "0.00");
        }
        byte[] c = comm("0000000042");
        InquireAccountsForCustomer.run(c, con, task);
        assertEquals(20, count(c));
        assertEquals("Y0Y", flags(c));
    }

    @Test
    void fetchErrorRollsBackWithCode3() throws Exception {
        con.createStatement().execute("UPDATE ACCOUNT SET ACCOUNT_OPENED = NULL WHERE ACCOUNT_NUMBER = '00000009'");
        byte[] c = comm("0000000042");
        InquireAccountsForCustomer.run(c, con, task);
        assertEquals("N3N", flags(c));
        assertEquals(0, count(c));
        assertEquals(1, rollbacks);
    }

    @Test
    void openErrorRollsBackWithCode2() throws Exception {
        con.createStatement().execute("DROP TABLE ACCOUNT");
        byte[] c = comm("0000000042");
        InquireAccountsForCustomer.run(c, con, task);
        assertEquals("N2N", flags(c));
        assertEquals(1, rollbacks);
    }
}
