package com.ibm.bankofz.cics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.bridge.copybook.cics.Delacc;
import com.ibm.bankofz.bridge.copybook.cics.Delcus;
import com.ibm.bankofz.bridge.copybook.cics.Inqacccu;
import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

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

class DeleteCustomerTest {
    Connection con;
    final List<String> cics = new ArrayList<>();

    final CicsTask task = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return LocalDateTime.of(2024, 5, 6, 7, 8, 9); }
        @Override public RuntimeException abend(String code) { return new DeleteAccountTest.Abend(code); }
        @Override public void link(String program, byte[] c) {
            switch (program) {
                case "INQCUST" -> {
                    boolean known = !Text.decode(c, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037)
                            .equals("0000000099");
                    Text.encode(known ? "Y " : "N1", c, Inqcustz.INQCUST_INQ_SUCCESS.offset(), 2, Ebcdic.CP037);
                    cics.add("LINK INQCUST");
                }
                case "INQACCCU" -> {
                    ByteBuffer.wrap(c).putInt(0, 2);
                    Text.encode("00000007", c, Inqacccu.COMM_ACCNO.offset(), 8, Ebcdic.CP037);
                    Text.encode("00000009", c, Inqacccu.COMM_ACCNO.offset() + 98, 8, Ebcdic.CP037);
                    cics.add("LINK INQACCCU");
                }
                default -> cics.add("LINK " + program + " "
                        + Text.decode(c, Delacc.DELACC_ACCNO.offset(), 8, Ebcdic.CP037));
            }
        }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:delcus" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            for (String table : new String[] {"CUSTOMER", "PROCTRAN"}) {
                Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(" + table + ") \\((.*?)\\n\\s*\\)", Pattern.DOTALL)
                        .matcher(ddl);
                assertTrue(m.find(), table);
                st.execute("CREATE TABLE " + table + " (" + m.group(2) + ")");
            }
            st.execute("INSERT INTO CUSTOMER VALUES ('CUST', '987654', '0000000042', 'Dr', 'Ada', 'Lovelace King',"
                    + " 19851210, '0123 456', '1 Main St', '', 'Leeds', 'LS1', 'UK', 'ACTIVE', 20240501, 400, 20240520)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String custno) {
        byte[] c = new byte[Delcus.LENGTH];
        Text.encode("", c, 0, c.length, Ebcdic.CP037);
        Text.encode("987654", c, Delcus.COMM_SCODE.offset(), 6, Ebcdic.CP037);
        Text.encode(custno, c, Delcus.COMM_CUSTNO.offset(), 10, Ebcdic.CP037);
        return c;
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
    void deletesAccountsThenCustomerAndWritesOdc() throws Exception {
        byte[] c = comm("0000000042");
        DeleteCustomer.run(c, con, task);
        assertEquals(List.of("LINK INQCUST", "LINK INQACCCU", "LINK DELACC 00000007", "LINK DELACC 00000009"), cics);
        assertEquals("CUST9876540000000042Dr        Ada", Text.decode(c, 0, 33, Ebcdic.CP037));
        assertEquals("10121985", Text.decode(c, Delcus.COMM_DOB_DAY.offset(), 8, Ebcdic.CP037));
        assertEquals("ACTIVE    0105202440020052024Y ", Text.decode(c, Delcus.COMM_STATUS.offset(), 31, Ebcdic.CP037));
        assertEquals("", query("SELECT * FROM CUSTOMER"));
        assertEquals("PRTR|987654|00000000|2024-05-06|070809|000000004711|ODC|9876540000000042Ada Lovelace K10/12/1985"
                + "|0.00\n", query("SELECT * FROM PROCTRAN"));
        con.rollback();
        assertEquals(1, query("SELECT * FROM CUSTOMER").lines().count(), "DELCUS leaves the commit to the CICS task");
    }

    @Test
    void unknownCustomerReturnsInqcustFailCode() throws Exception {
        byte[] c = comm("0000000099");
        DeleteCustomer.run(c, con, task);
        assertEquals("N1", Text.decode(c, Delcus.COMM_DEL_SUCCESS.offset(), 2, Ebcdic.CP037));
        assertEquals(List.of("LINK INQCUST"), cics);
    }

    @Test
    void vanishedRowStillSucceedsWithoutProctran() throws Exception {
        con.createStatement().execute("DELETE FROM CUSTOMER");
        byte[] c = comm("0000000042");
        DeleteCustomer.run(c, con, task);
        assertEquals("Y ", Text.decode(c, Delcus.COMM_DEL_SUCCESS.offset(), 2, Ebcdic.CP037));
        assertEquals("", query("SELECT * FROM PROCTRAN"));
    }

    @Test
    void sqlFailuresAbend() throws Exception {
        con.createStatement().execute("DROP TABLE PROCTRAN");
        assertEquals("HWPT", assertThrows(DeleteAccountTest.Abend.class,
                () -> DeleteCustomer.run(comm("0000000042"), con, task)).code);
        con.rollback();
        con.createStatement().execute("UPDATE CUSTOMER SET CUSTOMER_PHONE = NULL");
        assertEquals("WPV6", assertThrows(DeleteAccountTest.Abend.class,
                () -> DeleteCustomer.run(comm("0000000042"), con, task)).code);
    }
}
