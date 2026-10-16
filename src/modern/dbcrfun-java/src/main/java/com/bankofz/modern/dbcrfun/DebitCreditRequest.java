package com.bankofz.modern.dbcrfun;

import java.math.BigDecimal;

/**
 * Input half of the PAYDBCR COMMAREA (src/base/cics/copy/PAYDBCR.cpy).
 *
 * @param accountNumber COMM-ACCNO, 8 digits (shorter values are zero-padded, as the z/OS Connect mapping does)
 * @param amount        COMM-AMT, negative = debit, zero or positive = credit
 * @param sortCode      COMM-SORTC as sent by the caller; ignored by DBCRFUN (see {@link DebitCreditService#SORTCODE})
 * @param facilityType  COMM-FACILTYPE; {@link #FACILTYPE_PAYMENT} (496) marks a payment, anything else is a teller request
 */
public record DebitCreditRequest(
        String accountNumber,
        BigDecimal amount,
        String sortCode,
        int facilityType,
        String applId,
        String userId,
        String facilityName,
        String networkId,
        String description) {

    public static final int FACILTYPE_PAYMENT = 496;

    public static DebitCreditRequest teller(String accountNumber, BigDecimal amount) {
        return new DebitCreditRequest(accountNumber, amount, null, 0, "", "", "", "", "");
    }

    /** What the z/OS Connect deposit operation sends: FACILTYPE 496, blank origin fields. */
    public static DebitCreditRequest payment(String accountNumber, BigDecimal amount, String description) {
        return new DebitCreditRequest(accountNumber, amount, null, FACILTYPE_PAYMENT, "", "", "", "", description);
    }

    public boolean isPayment() {
        return facilityType == FACILTYPE_PAYMENT;
    }
}
