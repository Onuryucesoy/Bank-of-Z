package com.ibm.bankofz.core.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** BANKZ.PROCTRAN row (PROCDB2.cpy declaration). TIME is CHAR(6) HHMMSS, REF CHAR(12). */
public record ProcTranRow(
        String eyecatcher,
        String sortCode,
        String accountNumber,
        LocalDate date,
        String time,
        String reference,
        String type,
        String description,
        BigDecimal amount) {
}
