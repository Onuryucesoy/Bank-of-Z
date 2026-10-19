package com.bankofz.migration.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code 03 ACCOUNT-DATA} of src/base/cics/copy/ACCOUNT.cpy, persisted as the Db2
 * table declared in ACCDB2.cpy (src/migration/db2/V001__target_schema.sql).
 *
 * <p>Money: {@code ACCOUNT-INTEREST-RATE PIC 9(4)V99} and the balances
 * {@code PIC S9(10)V99} are {@link BigDecimal} with scale 2; the overdraft limit
 * {@code PIC 9(8)} is a {@link BigDecimal} with scale 0. Values are never
 * rounded: more decimals or more integer digits than the PIC allows is an error.
 * Note that the Db2 DECLARE in ACCDB2.cpy is narrower (DECIMAL(4,2) and
 * DECIMAL(10,2)), see docs/migration/phase-1-data-layer.md section 2.2.
 *
 * <p>Dates: {@code ACCOUNT-OPENED}, {@code ACCOUNT-LAST-STMT-DATE} and
 * {@code ACCOUNT-NEXT-STMT-DATE} are {@code PIC 9(8)} items that are
 * {@code REDEFINES}d by {@code DAY/MONTH/YEAR} groups, i.e. DDMMYYYY
 * ({@link CobolDates#fromDdMmYyyy(int)}); in Db2 they are DATE columns.
 *
 * <p>OpenAPI {@code Account}: accountNumber, sortCode, customerNumber,
 * accountType, interestRate, openingDate, overdraftLimit, availableBalance and
 * actualBalance (the {@code Balance} schema) are derived from these components.
 */
public record AccountRecord(
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-CUST-NO", value = AccountRecord.PIC_CUSTOMER_NUMBER)
        String customerNumber,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-SORT-CODE", value = AccountRecord.PIC_SORTCODE)
        String sortCode,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-NUMBER", value = AccountRecord.PIC_NUMBER)
        String accountNumber,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-TYPE", value = AccountRecord.PIC_TYPE)
        String accountType,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-INTEREST-RATE", value = AccountRecord.PIC_INTEREST_RATE)
        BigDecimal interestRate,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-OPENED", value = AccountRecord.PIC_DATE,
                redefinedBy = "ACCOUNT-OPENED-GROUP")
        LocalDate opened,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-OVERDRAFT-LIMIT", value = AccountRecord.PIC_OVERDRAFT_LIMIT)
        BigDecimal overdraftLimit,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-LAST-STMT-DATE", value = AccountRecord.PIC_DATE,
                redefinedBy = "ACCOUNT-LAST-STMT-GROUP")
        LocalDate lastStatementDate,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-NEXT-STMT-DATE", value = AccountRecord.PIC_DATE,
                redefinedBy = "ACCOUNT-NEXT-STMT-GROUP")
        LocalDate nextStatementDate,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-AVAILABLE-BALANCE", value = AccountRecord.PIC_BALANCE)
        BigDecimal availableBalance,
        @Pic(copybook = Copybooks.ACCOUNT, field = "ACCOUNT-ACTUAL-BALANCE", value = AccountRecord.PIC_BALANCE)
        BigDecimal actualBalance) {

    /** {@code 88 ACCOUNT-EYECATCHER-VALUE VALUE 'ACCT'}. */
    public static final String EYECATCHER = "ACCT";
    public static final String PIC_CUSTOMER_NUMBER = "9(10)";
    public static final String PIC_SORTCODE = "9(6)";
    public static final String PIC_NUMBER = "9(8)";
    public static final String PIC_TYPE = "X(8)";
    public static final String PIC_INTEREST_RATE = "9(4)V99";
    public static final String PIC_DATE = "9(8)";
    public static final String PIC_OVERDRAFT_LIMIT = "9(8)";
    public static final String PIC_BALANCE = "S9(10)V99";

    public AccountRecord {
        customerNumber = CobolFields.digits(customerNumber, PIC_CUSTOMER_NUMBER, "ACCOUNT-CUST-NO");
        sortCode = CobolFields.digits(sortCode, PIC_SORTCODE, "ACCOUNT-SORT-CODE");
        accountNumber = CobolFields.digits(accountNumber, PIC_NUMBER, "ACCOUNT-NUMBER");
        accountType = CobolFields.text(accountType, PIC_TYPE, "ACCOUNT-TYPE");
        interestRate = CobolFields.decimal(interestRate, PIC_INTEREST_RATE, "ACCOUNT-INTEREST-RATE");
        overdraftLimit = CobolFields.decimal(overdraftLimit, PIC_OVERDRAFT_LIMIT, "ACCOUNT-OVERDRAFT-LIMIT");
        availableBalance = CobolFields.decimal(availableBalance, PIC_BALANCE, "ACCOUNT-AVAILABLE-BALANCE");
        actualBalance = CobolFields.decimal(actualBalance, PIC_BALANCE, "ACCOUNT-ACTUAL-BALANCE");
        CobolDates.toDdMmYyyy(opened);
        CobolDates.toDdMmYyyy(lastStatementDate);
        CobolDates.toDdMmYyyy(nextStatementDate);
    }
}
