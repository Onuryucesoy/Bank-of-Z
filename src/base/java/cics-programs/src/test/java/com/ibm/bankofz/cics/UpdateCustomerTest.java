package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Updcust;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCustomerTest {
    Connection con;

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:updcust" + System.nanoTime() + ";MODE=DB2");
        con.setAutoCommit(false);
        String ddl = Files.readString(UpdateAccountTest.REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(CUSTOMER) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        assertTrue(m.find());
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute("SET SCHEMA BANKZ");
            st.execute("CREATE TABLE CUSTOMER (" + m.group(2) + ")");
            st.execute("INSERT INTO CUSTOMER VALUES ('CUST', '987654', '0000000042', 'Mr', 'John', 'Smith',"
                    + " 19800102, '0123', '1 Road', 'Flat 2', 'Town', 'AB1 2CD', 'UK', 'ACTIVE', 20210304, 1456,"
                    + " 20240115)");
        }
        con.commit();
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String custno, String title, String first, String last) {
        byte[] c = new byte[Updcust.LENGTH];
        Text.encode("", c, 0, Updcust.LENGTH, Ebcdic.CP037);
        set(c, Updcust.COMM_CUSTNO, custno);
        set(c, Updcust.COMM_TITLE, title);
        set(c, Updcust.COMM_FIRST_NAME, first);
        set(c, Updcust.COMM_LAST_NAME, last);
        set(c, Updcust.COMM_DOB, "00000000");
        return c;
    }

    static void set(byte[] c, FieldSpec f, String v) {
        Text.encode(v, c, f.offset(), f.length(), Ebcdic.CP037);
    }

    static String get(byte[] c, FieldSpec f) {
        return Text.decode(c, f.offset(), f.length(), Ebcdic.CP037);
    }

    String dbFirstName() throws Exception {
        try (ResultSet rs = con.createStatement().executeQuery("SELECT CUSTOMER_FIRST_NAME FROM CUSTOMER")) {
            rs.next();
            return rs.getString(1).strip();
        }
    }

    @Test
    void updatesNameKeepsBlankGroupsAndReturnsTheRow() throws Exception {
        byte[] c = comm("42", "Dr", "Jane", "Doe");
        UpdateCustomer.run(c, con);
        assertEquals("Y", get(c, Updcust.COMM_UPD_SUCCESS));
        assertEquals("CUST9876540000000042Dr        Jane", get(c, Updcust.COMM_EYE).concat(
                Text.decode(c, 4, 30, Ebcdic.CP037)));
        assertEquals("02011980", get(c, Updcust.COMM_DOB));
        assertEquals("0123", get(c, Updcust.COMM_PHONE).strip());
        assertEquals("1 Road", get(c, Updcust.COMM_ADDR_LINE1).strip());
        assertEquals("ACTIVE", get(c, Updcust.COMM_STATUS).strip());
        assertEquals("04032021", get(c, Updcust.COMM_CREATED_DATE));
        assertEquals("456", get(c, Updcust.COMM_CREDIT_SCORE), "SMALLINT 1456 into PIC 999 drops the high digit");
        byte[] rev = new byte[8];
        System.arraycopy(c, Updcust.COMM_CS_REVIEW_DATE.offset(), rev, 0, 8);
        assertEquals(String.format("%08X", 20240115) + "40404040", HexFormat.of().withUpperCase().formatHex(rev));
        assertEquals("Jane", dbFirstName());
    }

    @Test
    void dobIsUpdatedWhenYearIsNonZero() throws Exception {
        byte[] c = comm("0000000042", "", " ", " ");
        set(c, Updcust.COMM_ADDR_LINE1, "2 Lane");
        set(c, Updcust.COMM_DOB, "31121999");
        UpdateCustomer.run(c, con);
        assertEquals("Y", get(c, Updcust.COMM_UPD_SUCCESS));
        assertEquals("31121999", get(c, Updcust.COMM_DOB));
        assertEquals("John", get(c, Updcust.COMM_FIRST_NAME).strip());
        assertEquals("", get(c, Updcust.COMM_CITY).strip(), "address group is moved as a whole");
    }

    @Test
    void titleIsValidatedOnItsFirstNineBytes() throws Exception {
        byte[] bad = comm("42", "Mister", "Jane", "Doe");
        byte[] expected = bad.clone();
        set(expected, Updcust.COMM_UPD_SUCCESS, "N");
        set(expected, Updcust.COMM_UPD_FAIL_CD, "T");
        UpdateCustomer.run(bad, con);
        assertArrayEquals(expected, bad);
        byte[] truncated = comm("42", "Professorx", "Jane", "Doe");
        UpdateCustomer.run(truncated, con);
        assertEquals("Y", get(truncated, Updcust.COMM_UPD_SUCCESS));
    }

    @Test
    void failCodes() throws Exception {
        byte[] nothing = comm("42", "Mr", " Jane", "");
        UpdateCustomer.run(nothing, con);
        assertEquals("N4", Text.decode(nothing, 397, 2, Ebcdic.CP037));
        byte[] missing = comm("43", "Mr", "Jane", "Doe");
        UpdateCustomer.run(missing, con);
        assertEquals("N1", Text.decode(missing, 397, 2, Ebcdic.CP037));
        con.createStatement().execute("UPDATE CUSTOMER SET CUSTOMER_PHONE = NULL");
        byte[] nul = comm("42", "Mr", "Jane", "Doe");
        UpdateCustomer.run(nul, con);
        assertEquals("N2", Text.decode(nul, 397, 2, Ebcdic.CP037));
    }

    @Test
    void neverCommits() throws Exception {
        UpdateCustomer.run(comm("42", "Dr", "Jane", "Doe"), con);
        con.rollback();
        assertEquals("John", dbFirstName());
    }
}
