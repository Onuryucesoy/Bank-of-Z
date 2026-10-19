package com.ibm.bankofz.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BnkstmtTest {
    static final Path REPO = Path.of(System.getProperty("bankz.repoRoot"));
    Connection con;
    Statement st;

    @BeforeEach
    void db() throws Exception {
        con = DriverManager.getConnection("jdbc:h2:mem:stmt" + System.nanoTime() + ";MODE=DB2");
        st = con.createStatement();
        st.execute("CREATE SCHEMA BANKZ");
        String ddl = Files.readString(REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(\\w+) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        while (m.find()) {
            st.execute("CREATE TABLE BANKZ." + m.group(1) + " (" + m.group(2) + ")");
        }
        con.setSchema("BANKZ");
    }

    @AfterEach
    void close() throws Exception {
        con.close();
    }

    void account(String number, String cust, String type, String avail) throws Exception {
        st.execute("INSERT INTO ACCOUNT VALUES ('ACCT', '" + cust + "', '987654', '" + number + "', "
                + (type == null ? "NULL" : "'" + type + "'") + ", 1.5, DATE '2020-01-01', 500, NULL, NULL, "
                + avail + ", " + avail + ")");
    }

    void customer(String number, String title, String first) throws Exception {
        st.execute("INSERT INTO CUSTOMER VALUES ('CUST', '987654', '" + number + "', "
                + (title == null ? "NULL" : "'" + title + "'") + ", '" + first + "', 'Doe', 19800101, NULL,"
                + " '1 Main St', NULL, 'Springfield', 'AB1 2CD', 'UK', 'ACTIVE', 20200101, 500, NULL)");
    }

    void tran(String number, String date, String time, String type, String desc, String amount) throws Exception {
        st.execute("INSERT INTO PROCTRAN VALUES ('PRTR', '987654', '" + number + "', DATE '" + date + "', '" + time
                + "', 'REF000000001', '" + type + "', " + (desc == null ? "NULL" : "'" + desc + "'") + ", " + amount + ")");
    }

    List<String> run(int lineSize, String dateCard) throws Exception {
        StringWriter w = new StringWriter();
        new Bnkstmt(con, w, lineSize, 60).run("987654", dateCard, LocalDate.of(2026, 6, 15));
        return Arrays.asList(w.toString().split("\n"));
    }

    static String rec(String text) {
        return " " + Pli.chr(text, 132);
    }

    @Test
    void statementMatchesThePliFormatting() throws Exception {
        account("00000001", "0000000001", "ISA", "100.00");
        customer("0000000001", "Mr", "John");
        tran("00000001", "2026-06-03", "101500", "CR", "Salary", "50.00");
        tran("00000001", "2026-06-04", "090000", "DEB", null, "-20.00");
        tran("00000001", "2026-07-01", "090000", "DEB", "outside period", "-1.00");
        List<String> out = run(132, "202606");

        assertEquals("1BNKSTMT - BANK MONTHLY STATEMENT PROGRAM", out.get(0));
        assertTrue(out.stream().anyMatch(l -> l.startsWith(" STATEMENT PERIOD:") && l.contains("20260601") && l.endsWith("20260630")), out.toString());
        assertTrue(out.contains(rec(Pli.centre("BANK OF Z", 132))));
        assertTrue(out.contains(rec("STATEMENT DATE: 2026-06-30")));
        assertTrue(out.contains(rec("  NAME: Mr John Doe")));
        assertTrue(out.contains(rec("  ACCOUNT NUMBER: 987654-00000001")));
        assertTrue(out.contains(rec("  INTEREST RATE: 1.50%")));
        assertTrue(out.contains(rec("  OVERDRAFT LIMIT: $500")));
        assertTrue(out.contains(rec("2026-06-03 10:15:00 CR    REF000000001 " + Pli.chr("Salary", 30) + " 50.00")));
        assertTrue(out.contains(rec("2026-06-04 09:00:00 DEB   REF000000001 " + Pli.chr("N/A", 30) + " -20.00")));
        assertTrue(out.contains(rec("  OPENING BALANCE:        $     70.00")));
        assertTrue(out.contains(rec("  TOTAL CREDITS:          $     50.00")));
        assertTrue(out.contains(rec("  TOTAL DEBITS:           $     20.00")));
        assertTrue(out.contains(rec("  CLOSING BALANCE:        $    100.00")));
        assertTrue(out.contains(rec("  TRANSACTION COUNT:          2")));
        assertTrue(out.stream().noneMatch(l -> l.contains("outside period")));
    }

    @Test
    void creditTypesOtherThanCrAndCrdAreDebitsAsInThePli() throws Exception {
        account("00000001", "0000000001", "ISA", "10.00");
        customer("0000000001", "Mr", "John");
        tran("00000001", "2026-06-03", "101500", "CRE", "credit", "5.00");
        List<String> out = run(132, "202606");
        assertTrue(out.contains(rec("  TOTAL DEBITS:           $      5.00")));
    }

    @Test
    void februaryEndsOnThe28thAndNoDatecardUsesToday() throws Exception {
        assertTrue(run(132, "202402").stream().anyMatch(l -> l.startsWith(" STATEMENT PERIOD:") && l.endsWith("20240228")));
        List<String> out = run(132, null);
        assertTrue(out.contains(" DATECARD NOT FOUND - USING CURRENT DATE"));
        assertTrue(out.stream().anyMatch(l -> l.startsWith(" STATEMENT PERIOD:") && l.contains("20260615")));
    }

    @Test
    void nullTitleKeepsThePreviousHostVariableValue() throws Exception {
        account("00000001", "0000000001", "ISA", "1.00");
        account("00000002", "0000000002", "ISA", "1.00");
        customer("0000000001", "Mr", "John");
        customer("0000000002", null, "Jane");
        List<String> out = run(132, "202606");
        assertTrue(out.contains(rec("  NAME: Mr Jane Doe")), out.toString());
    }

    @Test
    void missingCustomerAndNullColumnWithoutIndicator() throws Exception {
        account("00000001", "0000000009", "ISA", "1.00");
        account("00000002", "0000000009", null, "1.00");
        List<String> out = run(132, "202606");
        String all = String.join("\n", out);
        assertTrue(out.contains(" WARNING: CUSTOMER NOT FOUND - SQLCODE: 100"), all);
        assertTrue(out.contains(rec("  NAME:   ")), all);
        assertTrue(out.stream().anyMatch(l -> l.substring(1).startsWith("ERROR FETCHING ACCOUNT - SQLCODE:") && l.endsWith("-305")), all);
        assertTrue(out.stream().anyMatch(l -> l.startsWith(" TOTAL STATEMENTS GENERATED:") && l.endsWith("1")), all);
    }

    @Test
    void pageBreakReprintsTheHeaderAndLongLinesWrapAtLinesize() throws Exception {
        account("00000001", "0000000001", "ISA", "1.00");
        customer("0000000001", "Mr", "John");
        for (int i = 0; i < 60; i++) {
            tran("00000001", "2026-06-03", String.format("10%04d", i), "DEB", "t" + i, "-1.00");
        }
        List<String> out = run(132, "202606");
        assertEquals(2, out.stream().filter(l -> l.contains("MONTHLY ACCOUNT STATEMENT")).count());
        assertTrue(out.stream().skip(1).anyMatch(l -> l.startsWith("1")));

        List<String> wrapped = run(120, "202606");
        String header = Pli.chr("DATE       TIME    TYPE  REFERENCE    DESCRIPTION                      AMOUNT", 132);
        int i = wrapped.indexOf(" " + header.substring(0, 120));
        assertTrue(i > 0);
        assertEquals(" " + header.substring(120), wrapped.get(i + 1));
    }

    @Test
    void everyPliLiteralIsCarriedOver() throws Exception {
        String pli = Files.readString(REPO.resolve("src/base/batch/pli/BNKSTMT.pli")).replaceAll("(?s)/\\*.*?\\*/", "");
        String java = Files.readString(REPO.resolve(
                "src/base/java/batch-stmt/src/main/java/com/ibm/bankofz/batch/Bnkstmt.java"));
        Set<String> javaLits = new HashSet<>();
        Matcher j = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(java);
        while (j.find()) {
            javaLits.add(j.group(1));
        }
        Matcher m = Pattern.compile("'([^']*)'(B)?").matcher(pli);
        int n = 0;
        while (m.find()) {
            if (m.group(2) != null) {
                continue;
            }
            assertTrue(javaLits.contains(m.group(1)), "PL/I literal '" + m.group(1) + "' not in Bnkstmt.java");
            n++;
        }
        assertTrue(n > 60, "found " + n);
    }
}
