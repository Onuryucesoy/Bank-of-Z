package com.bankofz.migration.model;

import java.util.Objects;

/** Output half of the NEWACCNO interface (src/base/cics/copy/NEWACCNO.cpy). */
public record NewAccountNumberResponse(
        @Pic(copybook = Copybooks.NEWACCNO, field = "NEWACCNO-FUNCTION", value = "X")
        CounterFunction function,
        @Pic(copybook = Copybooks.NEWACCNO, field = "ACCOUNT-NUMBER", value = NewAccountNumberResponse.PIC_NUMBER)
        String accountNumber,
        @Pic(copybook = Copybooks.NEWACCNO, field = "NEWACCNO-SUCCESS", value = "X")
        boolean success,
        @Pic(copybook = Copybooks.NEWACCNO, field = "NEWACCNO-FAIL-CODE", value = "X")
        char failCode) {

    public static final String PIC_NUMBER = "9(8)";

    public NewAccountNumberResponse {
        Objects.requireNonNull(function, "NEWACCNO-FUNCTION");
        accountNumber = CobolFields.digits(accountNumber, PIC_NUMBER, "ACCOUNT-NUMBER");
        if (success && failCode != ' ') {
            throw new IllegalArgumentException("NEWACCNO-FAIL-CODE must be blank on success");
        }
    }

    public char successFlag() {
        return success ? 'Y' : 'N';
    }
}
