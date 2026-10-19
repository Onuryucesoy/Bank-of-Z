package com.ibm.bankofz.cics;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.bridge.copybook.cics.Crecust;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreateCustomerTest {
    static final LocalDateTime NOW = LocalDateTime.of(2024, 5, 6, 7, 8, 9);
    static final CreateCustomer.CreditCheck GOOD = comm -> new CreateCustomer.Credit(' ', 300 + 400 + 501, 3);

    Connection con;
    final List<String> cics = new ArrayList<>();
    final CicsTask task = new CicsTask() {
        @Override public int taskNumber() { return 4711; }
        @Override public LocalDateTime now() { return NOW; }
        @Override public RuntimeException abend(String code) { return new DeleteAccountTest.Abend(code); }
        @Override public boolean enq(String name) { cics.add("ENQ " + name); return true; }
        @Override public boolean deq(String name) { cics.add("DEQ " + name); return true; }
    };

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:crecust" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            for (String table : new String[] {"CUSTOMER", "PROCTRAN", "CONTROL"}) {
                Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(" + table + ") \\((.*?)\\n\\s*\\)", Pattern.DOTALL)
                        .matcher(ddl);
                assertTrue(m.find(), table);
                st.execute("CREATE TABLE " + table + " (" + m.group(2) + ")");
            }
            st.execute("INSERT INTO CONTROL VALUES ('BANKZCUST987654', 41, NULL)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String title, String dob) {
        byte[] c = new byte[Crecust.LENGTH];
        Text.encode("", c, 0, c.length, Ebcdic.CP037);
        Text.encode(title, c, Crecust.COMM_TITLE.offset(), 10, Ebcdic.CP037);
        Text.encode("Ada", c, Crecust.COMM_FIRST_NAME.offset(), 50, Ebcdic.CP037);
        Text.encode("Lovelace King", c, Crecust.COMM_LAST_NAME.offset(), 50, Ebcdic.CP037);
        Text.encode(dob, c, Crecust.COMM_DOB_DAY.offset(), 8, Ebcdic.CP037);
        Text.encode("0123 456", c, Crecust.COMM_PHONE.offset(), 20, Ebcdic.CP037);
        Text.encode("1 Main St", c, Crecust.COMM_ADDR_LINE1.offset(), 50, Ebcdic.CP037);
        Text.encode("Leeds", c, Crecust.COMM_CITY.offset(), 50, Ebcdic.CP037);
        Text.encode("LS1", c, Crecust.COMM_POSTCODE.offset(), 10, Ebcdic.CP037);
        Text.encode("UK", c, Crecust.COMM_COUNTRY.offset(), 50, Ebcdic.CP037);
        Text.encode("ACTIVE", c, Crecust.COMM_STATUS.offset(), 10, Ebcdic.CP037);
        Text.encode("01052024000", c, Crecust.COMM_CREATED_DAY.offset(), 11, Ebcdic.CP037);
        return c;
    }

    static String text(byte[] c, int at, int len) {
        return Text.decode(c, at, len, Ebcdic.CP037);
    }

    int count(String table) throws Exception {
        try (ResultSet rs = con.createStatement().executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void createsCustomerFromTheNamedCounterAndWritesOccProctran() throws Exception {
        byte[] c = comm("Dr", "10121985");
        CreateCustomer.run(c, con, task, GOOD);
        int add = (int) (20 * new Random(4711).nextDouble()) + 1;
        String review = LocalDate.of(2024, 5, 6).plusDays(add).format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        assertEquals("CUST9876540000000042", text(c, 0, 20));
        assertEquals("400" + review + "Y ", text(c, Crecust.COMM_CREDIT_SCORE.offset(), 13));
        assertEquals(List.of("ENQ BANKZCUST987654 ", "DEQ BANKZCUST987654 "), cics);
        try (ResultSet rs = con.createStatement().executeQuery("SELECT * FROM CUSTOMER")) {
            assertTrue(rs.next());
            assertEquals("CUST|987654|0000000042|Dr|Ada|Lovelace King|19851210|0123 456|1 Main St|Leeds|LS1|UK|ACTIVE"
                    + "|20240501|400|" + review.substring(4) + review.substring(2, 4) + review.substring(0, 2),
                    String.join("|", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4).trim(),
                            rs.getString(5).trim(), rs.getString(6).trim(), rs.getString(7), rs.getString(8).trim(),
                            rs.getString(9).trim(), rs.getString(11).trim(), rs.getString(12).trim(),
                            rs.getString(13).trim(), rs.getString(14).trim(), rs.getString(15), rs.getString(16),
                            rs.getString(17)));
            assertFalse(rs.next());
        }
        try (ResultSet rs = con.createStatement().executeQuery("SELECT * FROM PROCTRAN")) {
            assertTrue(rs.next());
            assertEquals("PRTR|987654|00000000|2024-05-06|070809|000000004711|OCC", String.join("|",
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getDate(4).toString(), rs.getString(5),
                    rs.getString(6), rs.getString(7)));
            assertEquals("9876540000000042Ada Lovelace K10/12/1985", rs.getString(8).trim());
            assertEquals(0, rs.getBigDecimal(9).signum());
        }
        try (ResultSet rs = con.createStatement().executeQuery("SELECT CONTROL_VALUE_NUM FROM CONTROL")) {
            rs.next();
            assertEquals(42, rs.getInt(1));
        }
        con.rollback();
        assertEquals(0, count("CUSTOMER"), "CRECUST leaves the commit to the CICS task");
    }

    @Test
    void titleIsComparedOverAllTenBytes() throws Exception {
        for (String title : new String[] {"Professorx", "Captain"}) {
            byte[] c = comm(title, "10121985");
            byte[] expected = c.clone();
            Text.encode("NT", expected, Crecust.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
            CreateCustomer.run(c, con, task, GOOD);
            assertArrayEquals(expected, c, title);
        }
        assertEquals(0, count("CUSTOMER"));
        assertTrue(cics.isEmpty());
    }

    @Test
    void creditCheckFailuresZeroTheScoreAndStampToday() throws Exception {
        String[][] cases = {{"D", "ND"}, {" ", "NG"}};
        for (String[] k : cases) {
            byte[] c = comm("", "10121985");
            CreateCustomer.run(c, con, task, comm -> new CreateCustomer.Credit(k[0].charAt(0), 0, 0));
            assertEquals("00006052024" + k[1], text(c, Crecust.COMM_CREDIT_SCORE.offset(), 13));
        }
        assertEquals(0, count("CUSTOMER"));
    }

    @Test
    void dateOfBirthChecks() throws Exception {
        String[][] cases = {{"01011600", "NO"}, {"31022000", "NZ"}, {"07052024", "NY"}, {"01011873", "NO"}};
        for (String[] k : cases) {
            byte[] c = comm("Mr", k[0]);
            CreateCustomer.run(c, con, task, GOOD);
            assertEquals(k[1], text(c, Crecust.COMM_SUCCESS.offset(), 2), k[0]);
        }
        assertEquals(0, count("CUSTOMER"));
        assertTrue(cics.isEmpty());
    }

    @Test
    void missingCounterFailsWith4AndDequeues() throws Exception {
        con.createStatement().execute("DELETE FROM CONTROL");
        byte[] c = comm("Mr", "10121985");
        CreateCustomer.run(c, con, task, GOOD);
        assertEquals("N4", text(c, Crecust.COMM_SUCCESS.offset(), 2));
        assertEquals(List.of("ENQ BANKZCUST987654 ", "DEQ BANKZCUST987654 "), cics);
    }

    @Test
    void proctranFailureAbendsHwptAfterDequeue() throws Exception {
        con.createStatement().execute("DROP TABLE PROCTRAN");
        byte[] c = comm("Mr", "10121985");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> CreateCustomer.run(c, con, task, GOOD));
        assertEquals("HWPT", a.code);
        assertEquals(List.of("ENQ BANKZCUST987654 ", "DEQ BANKZCUST987654 "), cics);
    }
}
