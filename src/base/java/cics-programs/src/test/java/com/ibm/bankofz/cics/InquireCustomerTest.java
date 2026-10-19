package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

class InquireCustomerTest {
    Connection con;

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:inqcust" + System.nanoTime() + ";MODE=DB2");
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
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    static byte[] comm(String custno) {
        byte[] c = new byte[Inqcustz.LENGTH];
        Text.encode("", c, 0, Inqcustz.LENGTH, Ebcdic.CP037);
        Text.encode(custno, c, Inqcustz.INQCUST_CUSTNO.offset(), 10, Ebcdic.CP037);
        return c;
    }

    static String get(byte[] c, FieldSpec f) {
        return Text.decode(c, f.offset(), f.length(), Ebcdic.CP037);
    }

    @Test
    void returnsTheCustomer() {
        byte[] c = comm("0000000042");
        InquireCustomer.run(c, con, DeleteAccountTest.TASK);
        assertEquals("CUST9876540000000042Mr        John", Text.decode(c, 0, 34, Ebcdic.CP037));
        assertEquals("02011980", get(c, Inqcustz.INQCUST_DOB));
        assertEquals("AB1 2CD", get(c, Inqcustz.INQCUST_POSTCODE).strip());
        assertEquals("04032021", get(c, Inqcustz.INQCUST_CREATED_DATE));
        assertEquals("456", get(c, Inqcustz.INQCUST_CREDIT_SCORE));
        assertEquals("15012024", get(c, Inqcustz.INQCUST_CS_REVIEW_DT), "unlike UPDCUST, a real DDMMYYYY date");
        assertEquals("Y0", Text.decode(c, 397, 2, Ebcdic.CP037));
    }

    @Test
    void notFoundBlanksTheTextFields() {
        byte[] c = comm("0000000043");
        Text.encode("Dr", c, Inqcustz.INQCUST_TITLE.offset(), 10, Ebcdic.CP037);
        Text.encode("EYE!", c, 0, 4, Ebcdic.CP037);
        byte[] expected = comm("0000000043");
        Text.encode("EYE!", expected, 0, 4, Ebcdic.CP037);
        Text.encode("987654", expected, 4, 6, Ebcdic.CP037);
        Text.encode("", expected, 4, 6, Ebcdic.CP037);
        Text.encode("N1", expected, 397, 2, Ebcdic.CP037);
        InquireCustomer.run(c, con, DeleteAccountTest.TASK);
        assertArrayEquals(expected, c);
    }

    @Test
    void randomAndLastCustomerFailWithCode9BecauseSortCode2IsNeverSet() {
        for (String n : new String[] {"0000000000", "9999999999"}) {
            byte[] c = comm(n);
            InquireCustomer.run(c, con, DeleteAccountTest.TASK);
            assertEquals("N9", Text.decode(c, 397, 2, Ebcdic.CP037), n);
        }
    }

    @Test
    void randomCustomerIsFoundWhenSortCode000000HasRows() throws Exception {
        con.createStatement().execute("INSERT INTO CUSTOMER VALUES ('CUST', '000000', '0000000002', 'Ms', 'A',"
                + " 'B', 19900101, '', '', '', '', '', '', '', 20200101, 1, 20200101)");
        con.createStatement().execute("INSERT INTO CUSTOMER VALUES ('CUST', '987654', '0000000001', 'Ms', 'Ann',"
                + " 'Lee', 19900101, '', 'x', '', '', '', '', '', 20200101, 1, 20200101)");
        byte[] c = comm("0000000000");
        InquireCustomer.run(c, con, DeleteAccountTest.TASK);
        assertEquals("Y0", Text.decode(c, 397, 2, Ebcdic.CP037));
        assertEquals("0000000001", get(c, Inqcustz.INQCUST_CUSTNO), "(2 - 1) * RANDOM + 1 truncates to 1");
    }

    @Test
    void sqlErrorAbendsCvr1() throws Exception {
        con.createStatement().execute("UPDATE CUSTOMER SET CUSTOMER_PHONE = NULL");
        DeleteAccountTest.Abend a = assertThrows(DeleteAccountTest.Abend.class,
                () -> InquireCustomer.run(comm("0000000042"), con, DeleteAccountTest.TASK));
        assertEquals("CVR1", a.code);
    }
}
