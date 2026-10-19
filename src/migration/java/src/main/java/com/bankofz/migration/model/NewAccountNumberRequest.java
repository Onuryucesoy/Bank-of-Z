package com.bankofz.migration.model;

import java.util.Objects;

/**
 * Input half of the NEWACCNO interface (src/base/cics/copy/NEWACCNO.cpy):
 * {@code NEWACCNO-FUNCTION PIC X} and {@code ACCOUNT-NUMBER PIC 9(8)}.
 */
public record NewAccountNumberRequest(
        @Pic(copybook = Copybooks.NEWACCNO, field = "NEWACCNO-FUNCTION", value = "X")
        CounterFunction function,
        @Pic(copybook = Copybooks.NEWACCNO, field = "ACCOUNT-NUMBER", value = NewAccountNumberRequest.PIC_NUMBER)
        String accountNumber) {

    public static final String PIC_NUMBER = "9(8)";

    public NewAccountNumberRequest {
        Objects.requireNonNull(function, "NEWACCNO-FUNCTION");
        accountNumber = CobolFields.digits(accountNumber == null ? "0" : accountNumber,
                PIC_NUMBER, "ACCOUNT-NUMBER");
    }
}
