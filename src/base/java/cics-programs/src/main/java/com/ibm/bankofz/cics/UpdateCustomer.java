package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Updcust;
import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * UPDCUST: validates the title, merges the non-blank COMMAREA groups into the CUSTOMER row, updates it and
 * returns the row in the 399-byte UPDCUST COMMAREA (CP037). Fail codes: T title, 4 nothing to update,
 * 1 not found, 2 SELECT error, 3 UPDATE error. No commit or rollback: the caller's unit of work owns it.
 */
public final class UpdateCustomer {
    static final Set<String> TITLES =
            Set.of("Professor", "Mr", "Mrs", "Miss", "Ms", "Dr", "Drs", "Lord", "Sir", "Lady", "");
    private static final String[] COLUMNS = {"CUSTOMER_EYECATCHER", "CUSTOMER_SORTCODE", "CUSTOMER_NUMBER",
        "CUSTOMER_TITLE", "CUSTOMER_FIRST_NAME", "CUSTOMER_LAST_NAME", "CUSTOMER_DATE_OF_BIRTH", "CUSTOMER_PHONE",
        "CUSTOMER_ADDR_LINE1", "CUSTOMER_ADDR_LINE2", "CUSTOMER_CITY", "CUSTOMER_POSTCODE", "CUSTOMER_COUNTRY",
        "CUSTOMER_STATUS", "CUSTOMER_CREATED_DATE", "CUSTOMER_CREDIT_SCORE", "CUSTOMER_CS_REVIEW_DATE"};
    private static final FieldSpec[] TEXT = {Updcust.COMM_EYE, Updcust.COMM_SCODE, Updcust.COMM_CUSTNO,
        Updcust.COMM_TITLE, Updcust.COMM_FIRST_NAME, Updcust.COMM_LAST_NAME, null, Updcust.COMM_PHONE,
        Updcust.COMM_ADDR_LINE1, Updcust.COMM_ADDR_LINE2, Updcust.COMM_CITY, Updcust.COMM_POSTCODE,
        Updcust.COMM_COUNTRY, Updcust.COMM_STATUS};

    private UpdateCustomer() {
    }

    public static void run(byte[] comm, Connection con) {
        // WS-UNSTR-TITLE is PIC X(9): only the first 9 bytes of COMM-TITLE are validated.
        if (!TITLES.contains(rtrim(text(comm, Updcust.COMM_TITLE).substring(0, 9)))) {
            fail(comm, 'T');
            return;
        }
        if (first(comm, Updcust.COMM_FIRST_NAME) == ' ' && first(comm, Updcust.COMM_LAST_NAME) == ' '
                && first(comm, Updcust.COMM_ADDR_LINE1) == ' ') {
            fail(comm, '4');
            return;
        }
        String scode = text(comm, Updcust.COMM_SCODE);
        String sortcode = scode.isBlank() && !scode.contains("\0") || scode.chars().allMatch(c -> c == 0)
                ? UpdateAccount.SORTCODE : scode;
        // UNSTRING COMM-CUSTNO DELIMITED BY ' ' INTO DESIRED-CUSTNO (PIC 9(10)): right-justified, zero-filled.
        String token = text(comm, Updcust.COMM_CUSTNO);
        int sp = token.indexOf(' ');
        token = sp < 0 ? token : token.substring(0, sp);
        String custno = "0".repeat(10 - token.length()) + token;

        Object[] row = new Object[COLUMNS.length];
        try (PreparedStatement ps = con.prepareStatement("SELECT " + String.join(", ", COLUMNS)
                + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")) {
            ps.setString(1, sortcode);
            ps.setString(2, custno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    fail(comm, '1');
                    return;
                }
                for (int i = 0; i < row.length; i++) {
                    row[i] = i == 6 || i >= 14 ? (Object) rs.getInt(i + 1) : rs.getString(i + 1);
                    if (rs.wasNull()) {
                        fail(comm, '2'); // no indicator variables: SQLCODE -305
                        return;
                    }
                }
            }
        } catch (SQLException e) {
            fail(comm, '2');
            return;
        }

        if (first(comm, Updcust.COMM_FIRST_NAME) != ' ') {
            for (int i = 3; i <= 5; i++) {
                row[i] = text(comm, TEXT[i]);
            }
        }
        if (first(comm, Updcust.COMM_PHONE) != ' ') {
            row[7] = text(comm, Updcust.COMM_PHONE);
        }
        if (first(comm, Updcust.COMM_ADDR_LINE1) != ' ') {
            for (int i = 8; i <= 12; i++) {
                row[i] = text(comm, TEXT[i]);
            }
        }
        if (first(comm, Updcust.COMM_STATUS) != ' ') {
            row[13] = text(comm, Updcust.COMM_STATUS);
        }
        BigDecimal year = number(comm, Updcust.COMM_DOB_YEAR);
        if (year.signum() != 0) {
            row[6] = year.multiply(BigDecimal.valueOf(10000))
                    .add(number(comm, Updcust.COMM_DOB_MONTH).multiply(BigDecimal.valueOf(100)))
                    .add(number(comm, Updcust.COMM_DOB_DAY)).intValueExact();
        }

        try (PreparedStatement ps = con.prepareStatement("UPDATE CUSTOMER SET CUSTOMER_TITLE = ?,"
                + " CUSTOMER_FIRST_NAME = ?, CUSTOMER_LAST_NAME = ?, CUSTOMER_DATE_OF_BIRTH = ?, CUSTOMER_PHONE = ?,"
                + " CUSTOMER_ADDR_LINE1 = ?, CUSTOMER_ADDR_LINE2 = ?, CUSTOMER_CITY = ?, CUSTOMER_POSTCODE = ?,"
                + " CUSTOMER_COUNTRY = ?, CUSTOMER_STATUS = ? WHERE CUSTOMER_SORTCODE = ? AND CUSTOMER_NUMBER = ?")) {
            for (int i = 3; i <= 13; i++) {
                ps.setObject(i - 2, row[i]);
            }
            ps.setString(12, sortcode);
            ps.setString(13, custno);
            if (ps.executeUpdate() == 0) {
                fail(comm, '3');
                return;
            }
        } catch (SQLException e) {
            fail(comm, '3');
            return;
        }

        for (int i = 0; i < TEXT.length; i++) {
            if (TEXT[i] != null) {
                Text.encode((String) row[i], comm, TEXT[i].offset(), TEXT[i].length(), Ebcdic.CP037);
            }
        }
        yyyymmdd(comm, Updcust.COMM_CREATED_DAY, (Integer) row[14]);
        yyyymmdd(comm, Updcust.COMM_DOB_DAY, (Integer) row[6]);
        put(comm, Updcust.COMM_CREDIT_SCORE, BigDecimal.valueOf((Integer) row[15]));
        // MOVE HV-CUSTOMER-CS-REVIEW-DATE (S9(9) COMP) TO COMM-CS-REVIEW-DATE (8-byte group): a group move,
        // so the 4 binary bytes are copied and the rest is space-filled.
        int rev = (Integer) row[16];
        int off = Updcust.COMM_CS_REVIEW_DATE.offset();
        Text.fill(comm, off, 8, (byte) 0x40);
        comm[off] = (byte) (rev >> 24);
        comm[off + 1] = (byte) (rev >> 16);
        comm[off + 2] = (byte) (rev >> 8);
        comm[off + 3] = (byte) rev;
        Text.encode("Y", comm, Updcust.COMM_UPD_SUCCESS.offset(), 1, Ebcdic.CP037);
    }

    /** COMPUTE YEAR = D / 10000, MONTH = MOD(D / 100, 100), DAY = MOD(D, 100) into a DD MM YYYY group. */
    private static void yyyymmdd(byte[] comm, FieldSpec day, int d) {
        BigDecimal v = BigDecimal.valueOf(d);
        BigDecimal hundred = BigDecimal.valueOf(100);
        BigDecimal x = v.divide(hundred);
        put(comm, day, mod(v, hundred));
        put(comm, field(day.offset() + 2), mod(x, hundred));
        put(comm, field(day.offset() + 4), v.divide(BigDecimal.valueOf(10000)));
    }

    private static FieldSpec field(int offset) {
        for (FieldSpec f : new FieldSpec[] {Updcust.COMM_CREATED_MONTH, Updcust.COMM_CREATED_YEAR,
            Updcust.COMM_DOB_MONTH, Updcust.COMM_DOB_YEAR}) {
            if (f.offset() == offset) {
                return f;
            }
        }
        throw new IllegalArgumentException("offset " + offset);
    }

    /** FUNCTION MOD: x - y * FLOOR(x / y). */
    private static BigDecimal mod(BigDecimal x, BigDecimal y) {
        return x.subtract(y.multiply(x.divide(y, 0, RoundingMode.FLOOR)));
    }

    private static void put(byte[] comm, FieldSpec f, BigDecimal v) {
        ZonedDecimal.encode(Assign.fit(v, f.digits(), f.scale(), false, false), comm, f.offset(), f.digits(),
                f.scale(), ZonedDecimal.Sign.NONE);
    }

    private static BigDecimal number(byte[] comm, FieldSpec f) {
        return ZonedDecimal.decode(comm, f.offset(), f.digits(), f.scale(), ZonedDecimal.Sign.NONE);
    }

    private static void fail(byte[] comm, char code) {
        Text.encode("N" + code, comm, Updcust.COMM_UPD_SUCCESS.offset(), 2, Ebcdic.CP037);
    }

    private static char first(byte[] comm, FieldSpec f) {
        return text(comm, f).charAt(0);
    }

    private static String text(byte[] comm, FieldSpec f) {
        return Text.decode(comm, f.offset(), f.length(), Ebcdic.CP037);
    }

    private static String rtrim(String s) {
        int n = s.length();
        while (n > 0 && s.charAt(n - 1) == ' ') {
            n--;
        }
        return s.substring(0, n);
    }
}
