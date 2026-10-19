package com.ibm.bankofz.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.bridge.copybook.cics.Creacc;
import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
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
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateAccountTest {
    Connection con;
    final List<String> cics = new ArrayList<>();
    int existingAccounts = 2;
    boolean inqacccuFails;

    final CicsTask task = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return LocalDateTime.of(2024, 5, 6, 7, 8, 9); }
        @Override public RuntimeException abend(String code) { return new DeleteAccountTest.Abend(code); }
        @Override public boolean enq(String name) { cics.add("ENQ " + name); return true; }
        @Override public boolean deq(String name) { cics.add("DEQ " + name); return true; }
        @Override public void link(String program, byte[] c) {
            cics.add("LINK " + program);
            if (program.equals("INQCUST")) {
                boolean known = Text.decode(c, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037).equals("0000000042");
                Text.encode(known ? "Y" : "N", c, Inqcustz.INQCUST_INQ_SUCCESS.offset(), 1, Ebcdic.CP037);
                return;
            }
            if (inqacccuFails) {
                throw new IllegalStateException("PGMIDERR");
            }
            assertEquals(20, ByteBuffer.wrap(c).getInt(0));
            ByteBuffer.wrap(c).putInt(0, existingAccounts);
            Text.encode("Y", c, Inqacccu.COMM_SUCCESS.offset(), 1, Ebcdic.CP037);
        }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:creacc" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            for (String table : new String[] {"ACCOUNT", "PROCTRAN", "CONTROL"}) {
                Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(" + table + ") \\((.*?)\\n\\s*\\)", Pattern.DOTALL)
                        .matcher(ddl);
                assertTrue(m.find(), table);
                st.execute("CREATE TABLE " + table + " (" + m.group(2) + ")");
            }
            st.execute("INSERT INTO CONTROL VALUES ('987654-ACCOUNT-LAST', 41, NULL)");
            st.execute("INSERT INTO CONTROL VALUES ('987654-ACCOUNT-COUNT', 7, NULL)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String custno, String type) {
        byte[] c = new byte[Creacc.LENGTH];
        Text.encode("", c, 0, c.length, Ebcdic.CP037);
        Text.encode(custno, c, Creacc.COMM_CUSTNO.offset(), 10, Ebcdic.CP037);
        Text.encode(type, c, Creacc.COMM_ACC_TYPE.offset(), 8, Ebcdic.CP037);
        Text.encode("000150", c, Creacc.COMM_INT_RT.offset(), 6, Ebcdic.CP037);
        Text.encode("00000100", c, Creacc.COMM_OVERDR_LIM.offset(), 8, Ebcdic.CP037);
        ZonedDecimal.encode(new BigDecimal("-12.34"), c, 74, 12, 2, ZonedDecimal.Sign.TRAILING);
        ZonedDecimal.encode(new BigDecimal("56.78"), c, 86, 12, 2, ZonedDecimal.Sign.TRAILING);
        return c;
    }

    String status(byte[] c) {
        return Text.decode(c, Creacc.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
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
    void createsTheAccountFromTheControlCounters() throws Exception {
        byte[] c = comm("0000000042", "ISA");
        CreateAccount.run(c, con, task);
        assertEquals("ACCT000000004298765400000042ISA     0001500605202400000100060520240506" + "2024",
                Text.decode(c, 0, 74, Ebcdic.CP037));
        assertEquals(new BigDecimal("-12.34"), ZonedDecimal.decode(c, 74, 12, 2, ZonedDecimal.Sign.TRAILING));
        assertEquals("Y ", status(c));
        assertEquals(List.of("LINK INQCUST", "LINK INQACCCU", "ENQ BANKZACCT987654 ", "DEQ BANKZACCT987654 "), cics);
        assertEquals("ACCT|0000000042|987654|00000042|ISA|1.50|2024-05-06|100|2024-05-06|2024-06-05|-12.34|56.78\n",
                query("SELECT * FROM ACCOUNT"));
        assertEquals("987654-ACCOUNT-COUNT|8\n987654-ACCOUNT-LAST|42\n",
                query("SELECT CONTROL_NAME, CONTROL_VALUE_NUM FROM CONTROL ORDER BY 1"));
        assertEquals("PRTR|987654|00000042|2024-05-06|070809|000000004711|OCA|0000000042ISA     0605202405062024|0.00\n",
                query("SELECT * FROM PROCTRAN"));
        con.rollback();
        assertEquals("", query("SELECT * FROM ACCOUNT"), "CREACC leaves the commit to the CICS task");
    }

    @Test
    void validationFailures() throws Exception {
        assertEquals("N1", run(comm("0000000043", "ISA")));
        existingAccounts = 10;
        assertEquals("N8", run(comm("0000000042", "ISA")));
        existingAccounts = 9;
        assertEquals("NA", run(comm("0000000042", "CHEQUE")));
        inqacccuFails = true;
        assertEquals("N9", run(comm("0000000042", "ISA")));
        assertTrue(cics.stream().noneMatch(s -> s.startsWith("ENQ")));
        assertEquals("", query("SELECT * FROM ACCOUNT"));
    }

    @Test
    void typeCheckOnlyLooksAtThePrefix() throws Exception {
        assertEquals("Y ", run(comm("0000000042", "ISAXXXXX")));
    }

    @Test
    void missingCounterAbendsHncs() throws Exception {
        con.createStatement().execute("DELETE FROM CONTROL WHERE CONTROL_NAME = '987654-ACCOUNT-COUNT'");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> CreateAccount.run(comm("0000000042", "LOAN"), con, task));
        assertEquals("HNCS", a.code);
    }

    @Test
    void insertFailureIs7AndProctranFailureAbendsHwpt() throws Exception {
        con.createStatement().execute("DROP TABLE PROCTRAN");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> CreateAccount.run(comm("0000000042", "SAVING"), con, task));
        assertEquals("HWPT", a.code);
        con.createStatement().execute("DROP TABLE ACCOUNT");
        assertEquals("N7", run(comm("0000000042", "SAVING")));
        assertEquals("DEQ BANKZACCT987654 ", cics.get(cics.size() - 1));
    }

    String run(byte[] c) {
        CreateAccount.run(c, con, task);
        return status(c);
    }
}
