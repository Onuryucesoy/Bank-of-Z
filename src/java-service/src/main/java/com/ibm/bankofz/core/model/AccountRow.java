package com.ibm.bankofz.core.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * BANKZ.ACCOUNT row (ACCDB2.cpy declaration; DDL in .setup/jcl/cics/Db2-create.j2).
 * The DDL is authoritative for precision: INTEREST_RATE DECIMAL(6,2) and
 * balances DECIMAL(12,2), wider than the DECIMAL(4,2)/(10,2) in ACCDB2.cpy.
 */
public record AccountRow(
        String eyecatcher,
        String customerNumber,
        String sortCode,
        String accountNumber,
        String accountType,
        BigDecimal interestRate,
        LocalDate opened,
        Integer overdraftLimit,
        LocalDate lastStatement,
        LocalDate nextStatement,
        BigDecimal availableBalance,
        BigDecimal actualBalance) {
}
