package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Crecust;
import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.Set;

/**
 * CRECUST over the 399-byte CRECUST COMMAREA: title check, async credit check (OCR1-OCR5 over channel
 * CIPCREDCHANN), date-of-birth check, BANKZCUST named counter in CONTROL under ENQ, CUSTOMER insert and an
 * 'OCC' PROCTRAN row. Fail codes as in the COBOL: T title, A-H credit check, O/Z/Y date of birth, 3 ENQ,
 * 4 counter, 1 insert, 5 DEQ; a PROCTRAN failure abends HWPT so CICS backs out the counter and the insert.
 */
public final class CreateCustomer {
    static final String ENQ_NAME = "BANKZCUST" + UpdateAccount.SORTCODE + " ";
    static final String CONTROL_NAME = "BANKZCUST" + UpdateAccount.SORTCODE;
    static final Set<String> TITLES = Set.of("Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir",
            "Lady", "");
    private static final DateTimeFormatter DDMMYYYY = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HHmmss");

    /** Result of CREDIT-CHECK: a fail code (A-H) or the sum and count of the agency scores retrieved. */
    public record Credit(char failCode, int total, int retrieved) {
    }

    /** The five asynchronous credit-agency children; implementations PUT the COMMAREA into CIPA-CIPE. */
    public interface CreditCheck {
        Credit check(byte[] comm);
    }

    private CreateCustomer() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task, CreditCheck credit) {
        // EVALUATE COMM-TITLE compares all 10 bytes, so unlike UPDCUST 'Professorx' is rejected.
        String title = Text.decode(comm, Crecust.COMM_TITLE.offset(), 10, Ebcdic.CP037);
        if (title.charAt(9) != ' ' || !TITLES.contains(title.stripTrailing())) {
            status(comm, "NT");
            return;
        }
        LocalDate today = task.now().toLocalDate();
        Credit c = credit.check(comm);
        if (c.failCode() != ' ' || c.retrieved() == 0) {
            zoned(comm, Crecust.COMM_CREDIT_SCORE, BigDecimal.ZERO);
            Text.encode(today.format(DDMMYYYY), comm, Crecust.COMM_CS_REVIEW_DAY.offset(), 8, Ebcdic.CP037);
            status(comm, "N" + (c.failCode() == ' ' ? 'G' : c.failCode()));
            return;
        }
        zoned(comm, Crecust.COMM_CREDIT_SCORE, BigDecimal.valueOf(c.total() / c.retrieved()));
        int add = (int) (20 * new Random(task.taskNumber()).nextDouble()) + 1;
        Text.encode(today.plusDays(add).format(DDMMYYYY), comm, Crecust.COMM_CS_REVIEW_DAY.offset(), 8,
                Ebcdic.CP037);

        Character dob = dobError(comm, today);
        if (dob != null) {
            status(comm, "N" + dob);
            return;
        }
        if (!task.enq(ENQ_NAME)) {
            status(comm, "N3");
            return;
        }
        String custno;
        try {
            custno = nextCustomerNumber(con);
        } catch (SQLException e) {
            status(comm, "N4");
            task.deq(ENQ_NAME);
            return;
        }
        Text.encode(custno, comm, Crecust.COMM_NUMBER.offset(), 10, Ebcdic.CP037);
        try {
            insertCustomer(con, comm, custno);
        } catch (SQLException e) {
            status(comm, "N1");
            task.deq(ENQ_NAME);
            return;
        }
        try {
            insertProctran(con, comm, custno, task);
        } catch (SQLException e) {
            task.deq(ENQ_NAME);
            throw task.abend("HWPT");
        }
        if (!task.deq(ENQ_NAME)) {
            status(comm, "N5");
            return;
        }
        Text.encode(UpdateAccount.SORTCODE, comm, Crecust.COMM_SORTCODE.offset(), 6, Ebcdic.CP037);
        Text.encode("CUST", comm, Crecust.COMM_EYECATCHER.offset(), 4, Ebcdic.CP037);
        status(comm, "Y ");
    }

    /** DATE-OF-BIRTH-CHECK: O before 1601 or older than 150, Z not a real date, Y in the future. */
    static Character dobError(byte[] comm, LocalDate today) {
        int y = num(comm, Crecust.COMM_DOB_YEAR);
        if (y < 1601) {
            return 'O';
        }
        LocalDate dob;
        try {
            dob = LocalDate.of(y, num(comm, Crecust.COMM_DOB_MONTH), num(comm, Crecust.COMM_DOB_DAY));
        } catch (DateTimeException e) {
            return 'Z';
        }
        if (today.getYear() - y > 150) {
            return 'O';
        }
        return today.isBefore(dob) ? 'Y' : null;
    }

    private static String nextCustomerNumber(Connection con) throws SQLException {
        long value;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT CONTROL_VALUE_NUM FROM CONTROL WHERE CONTROL_NAME = ?")) {
            ps.setString(1, CONTROL_NAME);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("SQLCODE +100", "02000", 100);
                }
                value = rs.getLong(1) + 1;
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "UPDATE CONTROL SET CONTROL_VALUE_NUM = ? WHERE CONTROL_NAME = ?")) {
            ps.setLong(1, value);
            ps.setString(2, CONTROL_NAME);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("SQLCODE +100", "02000", 100);
            }
        }
        return String.format("%010d", Assign.fit(BigDecimal.valueOf(value), 10, 0, false, false).longValue());
    }

    private static void insertCustomer(Connection con, byte[] comm, String custno) throws SQLException {
        FieldSpec[] text = {Crecust.COMM_TITLE, Crecust.COMM_FIRST_NAME, Crecust.COMM_LAST_NAME, null,
            Crecust.COMM_PHONE, Crecust.COMM_ADDR_LINE1, Crecust.COMM_ADDR_LINE2, Crecust.COMM_CITY,
            Crecust.COMM_POSTCODE, Crecust.COMM_COUNTRY, Crecust.COMM_STATUS};
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO CUSTOMER (CUSTOMER_EYECATCHER,"
                + " CUSTOMER_SORTCODE, CUSTOMER_NUMBER, CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME,"
                + " CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE, CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2,"
                + " CUSTOMER_CITY, CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, CUSTOMER_STATUS, CUSTOMER_CREATED_DATE,"
                + " CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "CUST");
            ps.setString(2, UpdateAccount.SORTCODE);
            ps.setString(3, custno);
            for (int i = 0; i < text.length; i++) {
                if (text[i] == null) {
                    ps.setInt(7, ymd(comm, Crecust.COMM_DOB_YEAR, Crecust.COMM_DOB_MONTH, Crecust.COMM_DOB_DAY));
                } else {
                    ps.setString(4 + i, Text.decode(comm, text[i].offset(), text[i].length(), Ebcdic.CP037));
                }
            }
            ps.setInt(15, ymd(comm, Crecust.COMM_CREATED_YEAR, Crecust.COMM_CREATED_MONTH,
                    Crecust.COMM_CREATED_DAY));
            ps.setInt(16, num(comm, Crecust.COMM_CREDIT_SCORE));
            ps.setInt(17, ymd(comm, Crecust.COMM_CS_REVIEW_YEAR, Crecust.COMM_CS_REVIEW_MONTH,
                    Crecust.COMM_CS_REVIEW_DAY));
            ps.executeUpdate();
        }
    }

    private static void insertProctran(Connection con, byte[] comm, String custno, CicsTask task)
            throws SQLException {
        String name = (upToDoubleSpace(Text.decode(comm, Crecust.COMM_FIRST_NAME.offset(), 50, Ebcdic.CP037)) + " "
                + upToDoubleSpace(Text.decode(comm, Crecust.COMM_LAST_NAME.offset(), 50, Ebcdic.CP037)) + " ".repeat(14))
                .substring(0, 14);
        // The COBOL writes the DOB as DD/MM/YYYY although PROC-TRAN-DESC-CRECUS declares YYYY-MM-DD.
        String dob = Text.decode(comm, Crecust.COMM_DOB_DAY.offset(), 2, Ebcdic.CP037) + "/"
                + Text.decode(comm, Crecust.COMM_DOB_MONTH.offset(), 2, Ebcdic.CP037) + "/"
                + Text.decode(comm, Crecust.COMM_DOB_YEAR.offset(), 4, Ebcdic.CP037);
        LocalDateTime now = task.now();
        try (PreparedStatement ps = con.prepareStatement("INSERT INTO PROCTRAN (PROCTRAN_EYECATCHER,"
                + " PROCTRAN_SORTCODE, PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE,"
                + " PROCTRAN_DESC, PROCTRAN_AMOUNT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, "PRTR");
            ps.setString(2, UpdateAccount.SORTCODE);
            ps.setString(3, "00000000");
            ps.setDate(4, Date.valueOf(now.toLocalDate()));
            ps.setString(5, now.format(HHMMSS));
            ps.setString(6, String.format("%012d", task.taskNumber()));
            ps.setString(7, "OCC");
            ps.setString(8, UpdateAccount.SORTCODE + custno + name + dob);
            ps.setBigDecimal(9, BigDecimal.ZERO.setScale(2));
            ps.executeUpdate();
        }
    }

    /** STRING x DELIMITED BY '  '. */
    static String upToDoubleSpace(String s) {
        int i = s.indexOf("  ");
        return i < 0 ? s : s.substring(0, i);
    }

    private static int ymd(byte[] comm, FieldSpec y, FieldSpec m, FieldSpec d) {
        return num(comm, y) * 10000 + num(comm, m) * 100 + num(comm, d);
    }

    private static int num(byte[] comm, FieldSpec f) {
        return ZonedDecimal.decode(comm, f.offset(), f.length(), 0, ZonedDecimal.Sign.NONE).intValueExact();
    }

    private static void zoned(byte[] comm, FieldSpec f, BigDecimal v) {
        ZonedDecimal.encode(Assign.fit(v, f.length(), 0, false, false), comm, f.offset(), f.length(), 0,
                ZonedDecimal.Sign.NONE);
    }

    private static void status(byte[] comm, String s) {
        Text.encode(s, comm, Crecust.COMM_SUCCESS.offset(), 2, Ebcdic.CP037);
    }
}
