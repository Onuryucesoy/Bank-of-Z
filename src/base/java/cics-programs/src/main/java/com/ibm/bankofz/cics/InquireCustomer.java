package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Inqcustz;
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
import java.util.Random;

/**
 * INQCUST over the 403-byte INQCUSTZ COMMAREA. Customer number 0 asks for a random customer and 9999999999
 * for the last one; both first read the highest customer number for REQUIRED-SORT-CODE2, which the COBOL
 * never assigns (it stays 000000), so on real data they end with fail code 9. That is kept as is.
 * Fail codes: 1 not found, 9 last-customer lookup failed. An SQL error abends CVR1. The VSAM RLS abend
 * handler (AFCR/AFCS/AFCT) has no counterpart because this path reads Db2 only.
 */
public final class InquireCustomer {
    static final BigDecimal LAST = new BigDecimal("9999999999");
    private static final String SELECT = "SELECT CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE, CUSTOMER_NUMBER,"
            + " CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME, CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE,"
            + " CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2, CUSTOMER_CITY, CUSTOMER_POSTCODE, CUSTOMER_COUNTRY,"
            + " CUSTOMER_STATUS, CUSTOMER_CREATED_DATE, CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE"
            + " FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ?";
    private static final FieldSpec[] TEXT = {Inqcustz.INQCUST_EYE, Inqcustz.INQCUST_SCODE, Inqcustz.INQCUST_CUSTNO,
        Inqcustz.INQCUST_TITLE, Inqcustz.INQCUST_FIRST_NAME, Inqcustz.INQCUST_LAST_NAME, null,
        Inqcustz.INQCUST_PHONE, Inqcustz.INQCUST_ADDR_LINE1, Inqcustz.INQCUST_ADDR_LINE2, Inqcustz.INQCUST_CITY,
        Inqcustz.INQCUST_POSTCODE, Inqcustz.INQCUST_COUNTRY, Inqcustz.INQCUST_STATUS};
    private static final FieldSpec[] BLANKED_WHEN_NOT_FOUND = {Inqcustz.INQCUST_TITLE, Inqcustz.INQCUST_FIRST_NAME,
        Inqcustz.INQCUST_LAST_NAME, Inqcustz.INQCUST_PHONE, Inqcustz.INQCUST_ADDR_LINE1, Inqcustz.INQCUST_ADDR_LINE2,
        Inqcustz.INQCUST_CITY, Inqcustz.INQCUST_POSTCODE, Inqcustz.INQCUST_COUNTRY, Inqcustz.INQCUST_STATUS};

    private InquireCustomer() {
    }

    public static void run(byte[] comm, Connection con, CicsTask task) {
        status(comm, "N0");
        String scode = UpdateAccount.text(comm, Inqcustz.INQCUST_SCODE);
        boolean lowValues = true;
        for (int i = 0; i < 6; i++) {
            lowValues &= comm[Inqcustz.INQCUST_SCODE.offset() + i] == 0;
        }
        String sort = scode.equals("      ") || lowValues ? UpdateAccount.SORTCODE : scode;
        BigDecimal custno = ZonedDecimal.decode(comm, Inqcustz.INQCUST_CUSTNO.offset(), 10, 0,
                ZonedDecimal.Sign.NONE);
        String required = UpdateAccount.text(comm, Inqcustz.INQCUST_CUSTNO);
        boolean random = custno.signum() == 0;
        BigDecimal ncs = BigDecimal.ZERO;
        if (random || custno.equals(LAST)) {
            BigDecimal last = lastCustomer(con, task);
            if (last == null) {
                status(comm, "N9");
                return;
            }
            ncs = last;
            required = digits10(ncs);
        }
        Random rnd = new Random(task.taskNumber());
        int retry = 0;
        if (random) {
            required = digits10(ncs.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(rnd.nextDouble()))
                    .add(BigDecimal.ONE));
        }
        boolean retried = false;
        while (true) {
            Object[] row = select(con, task, sort, required);
            if (row != null) {
                status(comm, "Y0");
                fill(comm, row);
                return;
            }
            if (random) {
                if (retry < 1000) {
                    retry++;
                    required = digits10(ncs.subtract(BigDecimal.ONE)
                            .multiply(BigDecimal.valueOf(rnd.nextDouble())).add(BigDecimal.ONE));
                    continue;
                }
                status(comm, "N1");
                return;
            }
            if (custno.equals(LAST)) {
                if (!retried) {
                    // The COBOL re-reads the last customer but then reuses the old NCS-CUST-NO-VALUE.
                    lastCustomer(con, task);
                    retried = true;
                    continue;
                }
                // Deliberate deviation: the COBOL loops forever here (no branch sets EXIT-VSAM-READ).
                throw new IllegalStateException("INQCUST: last customer " + required + " vanished during inquiry");
            }
            status(comm, "N1");
            for (FieldSpec f : BLANKED_WHEN_NOT_FOUND) {
                Text.encode("", comm, f.offset(), f.length(), Ebcdic.CP037);
            }
            return;
        }
    }

    /** GET-LAST-CUSTOMER-DB2 with REQUIRED-SORT-CODE2 (always 000000); null on SQLCODE 100 or error. */
    private static BigDecimal lastCustomer(Connection con, CicsTask task) {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT CUSTOMER_NUMBER FROM CUSTOMER WHERE CUSTOMER_SORTCODE = ?"
                        + " ORDER BY CUSTOMER_NUMBER DESC FETCH FIRST 1 ROW ONLY")) {
            ps.setString(1, "000000");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new BigDecimal(rs.getString(1).strip()) : null;
            }
        } catch (SQLException | NumberFormatException e) {
            return null;
        }
    }

    private static Object[] select(Connection con, CicsTask task, String sort, String custno) {
        try (PreparedStatement ps = con.prepareStatement(SELECT + " AND CUSTOMER_NUMBER = ?")) {
            ps.setString(1, sort);
            ps.setString(2, custno);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Object[] row = new Object[17];
                for (int i = 0; i < row.length; i++) {
                    row[i] = i == 6 || i >= 14 ? (Object) rs.getInt(i + 1) : rs.getString(i + 1);
                    if (rs.wasNull()) {
                        throw task.abend("CVR1"); // SQLCODE -305: no indicator variables
                    }
                }
                return row;
            }
        } catch (SQLException e) {
            throw task.abend("CVR1");
        }
    }

    private static void fill(byte[] comm, Object[] row) {
        for (int i = 0; i < TEXT.length; i++) {
            if (TEXT[i] != null) {
                Text.encode((String) row[i], comm, TEXT[i].offset(), TEXT[i].length(), Ebcdic.CP037);
            }
        }
        ddmmyyyy(comm, Inqcustz.INQCUST_DOB.offset(), (Integer) row[6]);
        ddmmyyyy(comm, Inqcustz.INQCUST_CREATED_DATE.offset(), (Integer) row[14]);
        zoned(comm, Inqcustz.INQCUST_CREDIT_SCORE.offset(), 3, BigDecimal.valueOf((Integer) row[15]));
        ddmmyyyy(comm, Inqcustz.INQCUST_CS_REVIEW_DT.offset(), (Integer) row[16]);
    }

    /** COMPUTE YEAR = D / 10000, MONTH = MOD(D / 100, 100), DAY = MOD(D, 100). */
    private static void ddmmyyyy(byte[] comm, int off, int d) {
        BigDecimal v = BigDecimal.valueOf(d);
        BigDecimal h = BigDecimal.valueOf(100);
        BigDecimal x = v.divide(h);
        zoned(comm, off, 2, v.subtract(h.multiply(v.divide(h, 0, RoundingMode.FLOOR))));
        zoned(comm, off + 2, 2, x.subtract(h.multiply(x.divide(h, 0, RoundingMode.FLOOR))));
        zoned(comm, off + 4, 4, v.divide(BigDecimal.valueOf(10000)));
    }

    private static void zoned(byte[] comm, int off, int digits, BigDecimal v) {
        ZonedDecimal.encode(Assign.fit(v, digits, 0, false, false), comm, off, digits, 0, ZonedDecimal.Sign.NONE);
    }

    private static String digits10(BigDecimal v) {
        return String.format("%010d", Assign.fit(v, 10, 0, false, false).toBigInteger());
    }

    private static void status(byte[] comm, String s) {
        Text.encode(s, comm, Inqcustz.INQCUST_INQ_SUCCESS.offset(), 2, Ebcdic.CP037);
    }
}
