package com.bankofz.migration.model;

import java.util.Objects;

/**
 * Output half of the NEWCUSNO interface (src/base/cics/copy/NEWCUSNO.cpy):
 * {@code CUSTOMER-NUMBER PIC 9(10)}, {@code NEWCUSNO-SUCCESS PIC X} ('Y'/'N')
 * and {@code NEWCUSNO-FAIL-CODE PIC X} (blank when successful).
 */
public record NewCustomerNumberResponse(
        @Pic(copybook = Copybooks.NEWCUSNO, field = "NEWCUSNO-FUNCTION", value = "X")
        CounterFunction function,
        @Pic(copybook = Copybooks.NEWCUSNO, field = "CUSTOMER-NUMBER", value = NewCustomerNumberResponse.PIC_NUMBER)
        String customerNumber,
        @Pic(copybook = Copybooks.NEWCUSNO, field = "NEWCUSNO-SUCCESS", value = "X")
        boolean success,
        @Pic(copybook = Copybooks.NEWCUSNO, field = "NEWCUSNO-FAIL-CODE", value = "X")
        char failCode) {

    public static final String PIC_NUMBER = "9(10)";

    public NewCustomerNumberResponse {
        Objects.requireNonNull(function, "NEWCUSNO-FUNCTION");
        customerNumber = CobolFields.digits(customerNumber, PIC_NUMBER, "CUSTOMER-NUMBER");
        if (success && failCode != ' ') {
            throw new IllegalArgumentException("NEWCUSNO-FAIL-CODE must be blank on success");
        }
    }

    /** The {@code NEWCUSNO-SUCCESS} byte. */
    public char successFlag() {
        return success ? 'Y' : 'N';
    }
}
