package com.ibm.bankofz.core.model;

import java.math.BigDecimal;

/**
 * PROCTRAN.cpy (src/base/cics/copy) — 99-byte processed-transaction (audit)
 * record. Note PROC-TRAN-DATE is laid out YYYYMMDD, unlike ACCOUNT dates.
 */
public record ProcTran(
        String eyecatcher,
        String sortCode,
        long accountNumber,
        CobolDate date,
        int time,
        long reference,
        String type,
        String description,
        BigDecimal amount) {

    public static final String EYECATCHER = "PRTR";

    public ProcTranType typeCode() {
        return ProcTranType.fromCode(type);
    }
}
