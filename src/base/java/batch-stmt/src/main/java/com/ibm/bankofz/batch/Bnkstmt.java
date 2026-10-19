package com.ibm.bankofz.batch;

import com.ibm.bankofz.bridge.runtime.Assign;

import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Java port of {@code src/base/batch/pli/BNKSTMT.pli}, statement for statement, so its SYSPRINT can be
 * diffed against the PL/I report in the parallel run. Deliberately preserved PL/I behaviour:
 * February always ends on the 28th; "TOTAL STATEMENTS GENERATED" counts statements (PAGE_COUNT);
 * host variables keep their previous value when a column is NULL (null indicator set);
 * nullable columns without an indicator fail the fetch (SQLCODE -305); balances that do not fit
 * the DEC(10,2) host variables fail the fetch (-304); an interest rate that does not fit DEC(4,2)
 * leaves the previous value (+802, indicator -2).
 */
public final class Bnkstmt {
    static final int LINES_PER_PAGE = 55;
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final Connection con;
    private final PliPrint p;

    private String acctCustNumber = Pli.chr("", 10);
    private String acctSortcode = Pli.chr("", 6);
    private String acctNumber = Pli.chr("", 8);
    private String acctType = Pli.chr("", 8);
    private BigDecimal acctInterest = ZERO;
    private long acctOverdraft;
    private BigDecimal acctAvail = ZERO;
    private String title = Pli.chr("", 10);
    private String first = Pli.chr("", 50);
    private String last = Pli.chr("", 50);
    private String phone = Pli.chr("", 20);
    private String addr1 = Pli.chr("", 50);
    private String addr2 = Pli.chr("", 50);
    private String city = Pli.chr("", 50);
    private String postcode = Pli.chr("", 10);
    private String country = Pli.chr("", 50);
    private String tranDate;
    private String tranTime;
    private String tranRef;
    private String tranType;
    private String tranDesc;
    private BigDecimal tranAmount;

    private String stmtDate;
    private String periodFrom;
    private String periodTo;
    private int transCount;
    private BigDecimal totalCredits = ZERO;
    private BigDecimal totalDebits = ZERO;
    private int lineCount;
    private int pageCount;

    public Bnkstmt(Connection con, Writer sysprint, int lineSize, int pageSize) {
        this.con = con;
        this.p = new PliPrint(sysprint, lineSize, pageSize);
    }

    public static void main(String[] args) {
        int rc;
        try {
            String url = System.getProperty("bankz.db2.url");
            if (url == null) {
                throw new IllegalStateException("set -Dbankz.db2.url (e.g. jdbc:db2os390: under JZOS)");
            }
            String user = System.getenv("BANKZ_DB2_USER");
            try (Connection con = user == null ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, System.getenv("BANKZ_DB2_PASSWORD"));
                 Writer out = Dd.writer("SYSPRINT")) {
                con.setAutoCommit(false);
                con.setSchema(System.getProperty("bankz.db2.schema", "BANKZ"));
                Bnkstmt job = new Bnkstmt(con, out,
                        Integer.getInteger("bankz.linesize", PliPrint.DEFAULT_LINESIZE),
                        Integer.getInteger("bankz.pagesize", PliPrint.DEFAULT_PAGESIZE));
                job.run(Dd.firstRecord("SORTCODE"), Dd.firstRecord("DATECARD"), LocalDate.now());
            }
            rc = 0;
        } catch (Exception e) {
            System.err.println("BNKSTMT: " + e);
            rc = 12;
        }
        System.exit(rc);
    }

    /** {@code sortcodeCard}/{@code dateCard} null means the DD was empty (ENDFILE). */
    public void run(String sortcodeCard, String dateCard, LocalDate today) throws SQLException {
        p.skipList("BNKSTMT - BANK MONTHLY STATEMENT PROGRAM");
        p.skipList("==========================================");
        p.skipList(" ");
        getStatementPeriod(sortcodeCard, dateCard, today);
        p.skipList("INITIALIZING DB2 CONNECTION...");
        p.skipList("DB2 CONNECTION ESTABLISHED VIA DSN RUN");
        processAllAccounts();
        p.skipList("TERMINATING DB2 CONNECTION...");
        if (!con.getAutoCommit()) {
            con.commit();
        }
        p.skipList("DB2 CONNECTION TERMINATED");
        p.skipList(" ");
        p.skipList("BNKSTMT COMPLETED SUCCESSFULLY");
        p.skipList("TOTAL STATEMENTS GENERATED: ", pageCount);
        p.close();
    }

    private void getStatementPeriod(String sortcodeCard, String dateCard, LocalDate today) {
        if (sortcodeCard == null) {
            p.skipList("SORTCODE FILE NOT FOUND - USING DEFAULT");
            acctSortcode = "123456";
        } else {
            acctSortcode = Pli.chr(sortcodeCard, 80).substring(0, 6);
            p.skipList("SORT CODE FROM PARAMETER: ", acctSortcode);
        }
        if (dateCard == null) {
            p.skipList("DATECARD NOT FOUND - USING CURRENT DATE");
            String ymd = today.format(DateTimeFormatter.BASIC_ISO_DATE);
            String year = ymd.substring(0, 4);
            String month = ymd.substring(4, 6);
            String day = ymd.substring(6, 8);
            stmtDate = year + "-" + month + "-" + day;
            periodFrom = year + month + "01";
            periodTo = year + month + day;
            p.skipList("STATEMENT PERIOD: ", periodFrom, " TO ", periodTo);
            return;
        }
        String reportingMonth = Pli.chr(dateCard, 80).substring(0, 6);
        String year = reportingMonth.substring(0, 4);
        String month = reportingMonth.substring(4, 6);
        String lastDay;
        if (month.equals("02")) {
            lastDay = "28";
        } else if (month.equals("04") || month.equals("06") || month.equals("09") || month.equals("11")) {
            lastDay = "30";
        } else {
            lastDay = "31";
        }
        stmtDate = Pli.chr(year + "-" + month + "-" + lastDay, 10);
        periodFrom = year + month + "01";
        periodTo = year + month + lastDay;
        p.skipList("REPORTING MONTH FROM DATECARD: ", reportingMonth);
        p.skipList("STATEMENT PERIOD: ", periodFrom, " TO ", periodTo);
    }

    private void processAllAccounts() {
        p.skipList("PROCESSING ACCOUNTS FOR SORT CODE: ", acctSortcode);
        try (PreparedStatement ps = con.prepareStatement("SELECT ACCOUNT_EYECATCHER, ACCOUNT_CUSTOMER_NUMBER,"
                + " ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_TYPE, ACCOUNT_INTEREST_RATE, ACCOUNT_OPENED,"
                + " ACCOUNT_OVERDRAFT_LIMIT, ACCOUNT_LAST_STATEMENT, ACCOUNT_NEXT_STATEMENT,"
                + " ACCOUNT_AVAILABLE_BALANCE, ACCOUNT_ACTUAL_BALANCE FROM ACCOUNT"
                + " WHERE ACCOUNT_SORTCODE = ? ORDER BY ACCOUNT_NUMBER")) {
            ps.setString(1, acctSortcode);
            ResultSet rs;
            try {
                rs = ps.executeQuery();
            } catch (SQLException e) {
                p.skipList("ERROR OPENING ACCOUNT CURSOR - SQLCODE: ", e.getErrorCode());
                return;
            }
            try (rs) {
                while (true) {
                    try {
                        if (!rs.next()) {
                            break;
                        }
                        fetchAccount(rs);
                    } catch (SQLException e) {
                        p.skipList("ERROR FETCHING ACCOUNT - SQLCODE: ", e.getErrorCode());
                        break;
                    }
                    generateStatement();
                }
            }
        } catch (SQLException e) {
            p.skipList("ERROR OPENING ACCOUNT CURSOR - SQLCODE: ", e.getErrorCode());
        }
    }

    private void fetchAccount(ResultSet rs) throws SQLException {
        required(rs, 1);
        String custNumber = Pli.chr(required(rs, 2), 10);
        String sortcode = Pli.chr(required(rs, 3), 6);
        String number = Pli.chr(required(rs, 4), 8);
        String type = Pli.chr(required(rs, 5), 8);
        BigDecimal interest = rs.getBigDecimal(6);
        required(rs, 7);
        long overdraft = rs.getLong(8);
        boolean overdraftNull = rs.wasNull();
        BigDecimal avail = decimal(rs, 11, 10, 2);
        decimal(rs, 12, 10, 2);
        acctCustNumber = custNumber;
        acctSortcode = sortcode;
        acctNumber = number;
        acctType = type;
        if (interest != null && !Assign.overflows(interest, 4, 2)) {
            acctInterest = interest.setScale(2, RoundingMode.DOWN);
        }
        if (!overdraftNull) {
            acctOverdraft = overdraft;
        }
        acctAvail = avail;
    }

    private void generateStatement() {
        p.skipList("GENERATING STATEMENT FOR ACCOUNT: ", Pli.trim(acctNumber));
        transCount = 0;
        totalCredits = ZERO;
        totalDebits = ZERO;
        lineCount = 0;
        pageCount++;
        getCustomerInfo();
        printHeader();
        printAccountInfo();
        processTransactions();
        printSummary();
        printFooter();
    }

    private void getCustomerInfo() {
        try (PreparedStatement ps = con.prepareStatement("SELECT CUSTOMER_EYECATCHER, CUSTOMER_SORTCODE,"
                + " CUSTOMER_NUMBER, CUSTOMER_TITLE, CUSTOMER_FIRST_NAME, CUSTOMER_LAST_NAME,"
                + " CUSTOMER_DATE_OF_BIRTH, CUSTOMER_PHONE, CUSTOMER_ADDR_LINE1, CUSTOMER_ADDR_LINE2,"
                + " CUSTOMER_CITY, CUSTOMER_POSTCODE, CUSTOMER_COUNTRY, CUSTOMER_STATUS, CUSTOMER_CREATED_DATE,"
                + " CUSTOMER_CREDIT_SCORE, CUSTOMER_CS_REVIEW_DATE FROM CUSTOMER"
                + " WHERE CUSTOMER_NUMBER = ? AND CUSTOMER_SORTCODE = ?")) {
            ps.setString(1, acctCustNumber);
            ps.setString(2, acctSortcode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    p.skipList("WARNING: CUSTOMER NOT FOUND - SQLCODE: 100");
                    title = Pli.chr("", 10);
                    first = Pli.chr("", 50);
                    last = Pli.chr("", 50);
                    phone = Pli.chr("", 20);
                    addr1 = Pli.chr("", 50);
                    addr2 = Pli.chr("", 50);
                    city = Pli.chr("", 50);
                    postcode = Pli.chr("", 10);
                    country = Pli.chr("", 50);
                    return;
                }
                required(rs, 1);
                String t = rs.getString(4);
                String f = Pli.chr(required(rs, 5), 50);
                String l = Pli.chr(required(rs, 6), 50);
                String ph = rs.getString(8);
                String a1 = Pli.chr(required(rs, 9), 50);
                String a2 = rs.getString(10);
                String c = Pli.chr(required(rs, 11), 50);
                String pc = Pli.chr(required(rs, 12), 10);
                String co = Pli.chr(required(rs, 13), 50);
                required(rs, 14);
                required(rs, 15);
                if (rs.next()) {
                    throw new SQLException("more than one row", "21000", -811);
                }
                if (t != null) {
                    title = Pli.chr(t, 10);
                }
                first = f;
                last = l;
                if (ph != null) {
                    phone = Pli.chr(ph, 20);
                }
                addr1 = a1;
                if (a2 != null) {
                    addr2 = Pli.chr(a2, 50);
                }
                city = c;
                postcode = pc;
                country = co;
            }
        } catch (SQLException e) {
            p.skipList("ERROR RETRIEVING CUSTOMER - SQLCODE: ", e.getErrorCode());
            first = Pli.chr("", 50);
            last = Pli.chr("", 50);
            title = Pli.chr("", 10);
            phone = Pli.chr("", 20);
            addr1 = Pli.chr("", 50);
            addr2 = Pli.chr("", 50);
            city = Pli.chr("", 50);
            postcode = Pli.chr("", 10);
            country = Pli.chr("", 50);
        }
    }

    private void line(String s) {
        p.skipEdit(Pli.chr(s, 132));
        lineCount++;
    }

    private void checkPageBreak() {
        if (lineCount >= LINES_PER_PAGE) {
            p.page();
            lineCount = 0;
            printHeader();
        }
    }

    private void printHeader() {
        checkPageBreak();
        line(" ");
        line(Pli.centre("BANK OF Z", 132));
        line(Pli.centre("MONTHLY ACCOUNT STATEMENT", 132));
        line(Pli.centre("================================", 132));
        line(" ");
        line("STATEMENT DATE: " + stmtDate);
        line("STATEMENT PERIOD: " + periodFrom + " TO " + periodTo);
        line("PAGE: " + pageCount);
    }

    private void printAccountInfo() {
        checkPageBreak();
        line(" ");
        line("CUSTOMER INFORMATION:");
        line("  NAME: " + Pli.trim(title) + " " + Pli.trim(first) + " " + Pli.trim(last));
        line("  ADDRESS: " + Pli.trim(addr1));
        if (!Pli.trim(addr2).equals("")) {
            line("           " + Pli.trim(addr2));
        }
        line("           " + Pli.trim(city) + ", " + Pli.trim(postcode));
        line("           " + Pli.trim(country));
        line("  PHONE: " + Pli.trim(phone));
        line(" ");
        line("ACCOUNT INFORMATION:");
        line("  ACCOUNT NUMBER: " + Pli.trim(acctSortcode) + "-" + Pli.trim(acctNumber));
        line("  ACCOUNT TYPE: " + Pli.trim(acctType));
        line("  INTEREST RATE: " + Pli.charTrim(acctInterest, 2) + "%");
        line("  OVERDRAFT LIMIT: $" + acctOverdraft);
    }

    private void processTransactions() {
        checkPageBreak();
        line(" ");
        line("TRANSACTION HISTORY:");
        line(" ");
        line("DATE       TIME    TYPE  REFERENCE    " + "DESCRIPTION                      AMOUNT");
        line("--------------------------------------" + "--------------------------------------" + "----------");
        try (PreparedStatement ps = con.prepareStatement("SELECT PROCTRAN_EYECATCHER, PROCTRAN_SORTCODE,"
                + " PROCTRAN_NUMBER, PROCTRAN_DATE, PROCTRAN_TIME, PROCTRAN_REF, PROCTRAN_TYPE, PROCTRAN_DESC,"
                + " PROCTRAN_AMOUNT FROM PROCTRAN WHERE PROCTRAN_SORTCODE = ? AND PROCTRAN_NUMBER = ?"
                + " AND PROCTRAN_DATE >= ? AND PROCTRAN_DATE <= ? ORDER BY PROCTRAN_DATE, PROCTRAN_TIME")) {
            ps.setString(1, acctSortcode);
            ps.setString(2, acctNumber);
            ps.setDate(3, sqlDate(periodFrom));
            ps.setDate(4, sqlDate(periodTo));
            ResultSet rs;
            try {
                rs = ps.executeQuery();
            } catch (SQLException e) {
                p.skipList("ERROR OPENING TRANSACTION CURSOR - SQLCODE: ", e.getErrorCode());
                return;
            }
            try (rs) {
                while (true) {
                    try {
                        if (!rs.next()) {
                            break;
                        }
                        fetchTransaction(rs);
                    } catch (SQLException e) {
                        p.skipList("ERROR FETCHING TRANSACTION - SQLCODE: ", e.getErrorCode());
                        break;
                    }
                    printTransaction();
                    transCount++;
                    if (isCredit()) {
                        totalCredits = Assign.fit(totalCredits.add(tranAmount), 12, 2, true, false);
                    } else {
                        totalDebits = Assign.fit(totalDebits.add(tranAmount.abs()), 12, 2, true, false);
                    }
                }
            }
        } catch (SQLException e) {
            p.skipList("ERROR OPENING TRANSACTION CURSOR - SQLCODE: ", e.getErrorCode());
        }
        if (transCount == 0) {
            line("  NO TRANSACTIONS FOR THIS PERIOD");
        }
    }

    private void fetchTransaction(ResultSet rs) throws SQLException {
        required(rs, 1);
        Date d = rs.getDate(4);
        if (d == null) {
            throw new SQLException("null PROCTRAN_DATE without indicator", "22002", -305);
        }
        String time = Pli.chr(required(rs, 5), 6);
        String ref = Pli.chr(required(rs, 6), 12);
        String type = Pli.chr(required(rs, 7), 3);
        String desc = rs.getString(8);
        BigDecimal amount = decimal(rs, 9, 12, 2);
        tranDate = d.toLocalDate().toString();
        tranTime = time;
        tranRef = ref;
        tranType = type;
        tranDesc = desc == null ? null : Pli.chr(desc, 40);
        tranAmount = amount;
    }

    private boolean isCredit() {
        return tranType.equals(Pli.chr("CR", 3)) || tranType.equals("CRD");
    }

    private void printTransaction() {
        checkPageBreak();
        String formattedTime = Pli.chr(tranTime.substring(0, 2) + ":" + tranTime.substring(2, 4) + ":"
                + tranTime.substring(4, 6), 8);
        BigDecimal temp = isCredit() ? tranAmount : tranAmount.abs();
        String formattedAmount = Pli.chr(Pli.trim(Pli.chr(Pli.f(temp, 10, 2), 20)), 20);
        if (!isCredit()) {
            formattedAmount = Pli.chr("-" + formattedAmount, 20);
        }
        if (tranDesc == null) {
            tranDesc = Pli.chr("N/A", 40);
        }
        line(Pli.chr(tranDate, 10) + " " + formattedTime + " " + tranType + "   " + tranRef + " "
                + tranDesc.substring(0, 30) + " " + formattedAmount);
    }

    private void printSummary() {
        checkPageBreak();
        line(" ");
        line("================================");
        line("STATEMENT SUMMARY:");
        line("================================");
        line(" ");
        BigDecimal opening = Assign.fit(acctAvail, 12, 2, true, false);
        opening = Assign.fit(opening.add(totalDebits), 12, 2, true, false);
        opening = Assign.fit(opening.subtract(totalCredits), 12, 2, true, false);
        line("  OPENING BALANCE:        $" + Pli.f(opening, 10, 2));
        line("  TOTAL CREDITS:          $" + Pli.f(totalCredits, 10, 2));
        line("  TOTAL DEBITS:           $" + Pli.f(totalDebits, 10, 2));
        BigDecimal closing = opening;
        closing = Assign.fit(closing.subtract(totalDebits), 12, 2, true, false);
        closing = Assign.fit(closing.add(totalCredits), 12, 2, true, false);
        line("  CLOSING BALANCE:        $" + Pli.f(closing, 10, 2));
        line(" ");
        line("  AVAILABLE BALANCE:      $" + Pli.f(acctAvail, 10, 2));
        line("  TRANSACTION COUNT:      " + Pli.f(transCount, 5));
    }

    private void printFooter() {
        checkPageBreak();
        line(" ");
        line(" ");
        line(Pli.centre("*** END OF STATEMENT ***", 132));
        line(" ");
        line(Pli.centre("Thank you for banking with Bank of Z", 132));
        p.page();
        lineCount = 0;
    }

    private static String required(ResultSet rs, int col) throws SQLException {
        String v = rs.getString(col);
        if (v == null) {
            throw new SQLException("null column " + col + " without indicator", "22002", -305);
        }
        return v;
    }

    private static BigDecimal decimal(ResultSet rs, int col, int digits, int scale) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        if (v == null) {
            throw new SQLException("null column " + col + " without indicator", "22002", -305);
        }
        if (Assign.overflows(v, digits, scale)) {
            throw new SQLException("value of column " + col + " does not fit DEC(" + digits + "," + scale + ")",
                    "22003", -304);
        }
        return v.setScale(scale, RoundingMode.DOWN);
    }

    private static Date sqlDate(String yyyymmdd) {
        return Date.valueOf(LocalDate.parse(yyyymmdd, DateTimeFormatter.BASIC_ISO_DATE));
    }
}
