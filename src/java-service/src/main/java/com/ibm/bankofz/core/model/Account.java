package com.ibm.bankofz.core.model;

import java.math.BigDecimal;

/**
 * ACCOUNT.cpy (src/base/cics/copy) — 98-byte account record. Dates are
 * PIC 9(8) laid out DDMMYYYY; balances are PIC S9(10)V99 zoned.
 */
public record Account(
        String eyecatcher,
        long customerNumber,
        String sortCode,
        long accountNumber,
        String accountType,
        BigDecimal interestRate,
        CobolDate opened,
        long overdraftLimit,
        CobolDate lastStatementDate,
        CobolDate nextStatementDate,
        BigDecimal availableBalance,
        BigDecimal actualBalance) {

    public static final String EYECATCHER = "ACCT";

    public AccountKey key() {
        return new AccountKey(sortCode, accountNumber);
    }
}
