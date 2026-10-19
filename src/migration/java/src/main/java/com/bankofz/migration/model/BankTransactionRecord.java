package com.bankofz.migration.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * IMS HISTORY segment as exposed by src/base/ims/copy/IBGHIST.cpy (identical to
 * IBSHIST.cpy), persisted as BANK_TRANSACTION in V001__target_schema.sql.
 *
 * <p>{@code AMOUNT PIC S9(13)V9(2) COMP-3} is a {@link BigDecimal} with scale 2.
 * The {@code S9(18) COMP-5} ids are {@code long}. {@code TIMESTMP PIC X(23)} is
 * parsed with {@link CobolDates#fromImsTimestamp(String)}.
 * Transaction ids are {@code ACCID * 10^10 + sequence} (IBTRAN.cbl) and are kept.
 */
public record BankTransactionRecord(
        @Pic(copybook = Copybooks.IBGHIST, field = "TXID", value = BankTransactionRecord.PIC_ID, usage = Usage.COMP_5)
        long transactionId,
        @Pic(copybook = Copybooks.IBGHIST, field = "TIMESTMP", value = "X(23)")
        LocalDateTime timestamp,
        @Pic(copybook = Copybooks.IBGHIST, field = "TRANSTYP", value = "X(1)")
        char transactionType,
        @Pic(copybook = Copybooks.IBGHIST, field = "AMOUNT", value = BankTransactionRecord.PIC_AMOUNT,
                usage = Usage.COMP_3)
        BigDecimal amount,
        @Pic(copybook = Copybooks.IBGHIST, field = "REFTXID", value = BankTransactionRecord.PIC_ID, usage = Usage.COMP_5)
        long referenceTransactionId,
        @Pic(copybook = Copybooks.IBGHIST, field = "ACCID", value = BankTransactionRecord.PIC_ID, usage = Usage.COMP_5)
        long accountId) {

    public static final String PIC_ID = "S9(18)";
    public static final String PIC_AMOUNT = "S9(13)V9(2)";

    public BankTransactionRecord {
        CobolFields.integer(transactionId, PIC_ID, "TXID");
        Objects.requireNonNull(timestamp, "TIMESTMP");
        if ("dDwWt".indexOf(transactionType) < 0) {
            throw new IllegalArgumentException("TRANSTYP must be d/D/w/W/t: '" + transactionType + "'");
        }
        amount = CobolFields.decimal(amount, PIC_AMOUNT, "AMOUNT");
        CobolFields.integer(referenceTransactionId, PIC_ID, "REFTXID");
        CobolFields.integer(accountId, PIC_ID, "ACCID");
    }
}
