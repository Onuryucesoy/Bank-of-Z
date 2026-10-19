package com.bankofz.migration.model;

import java.util.Objects;

/**
 * Input half of the NEWCUSNO interface (src/base/cics/copy/NEWCUSNO.cpy):
 * {@code NEWCUSNO-FUNCTION PIC X} and {@code CUSTOMER-NUMBER PIC 9(10)}.
 * The number is only meaningful for {@link CounterFunction#ROLLBACK}.
 */
public record NewCustomerNumberRequest(
        @Pic(copybook = Copybooks.NEWCUSNO, field = "NEWCUSNO-FUNCTION", value = "X")
        CounterFunction function,
        @Pic(copybook = Copybooks.NEWCUSNO, field = "CUSTOMER-NUMBER", value = NewCustomerNumberRequest.PIC_NUMBER)
        String customerNumber) {

    public static final String PIC_NUMBER = "9(10)";

    public NewCustomerNumberRequest {
        Objects.requireNonNull(function, "NEWCUSNO-FUNCTION");
        customerNumber = CobolFields.digits(customerNumber == null ? "0" : customerNumber,
                PIC_NUMBER, "CUSTOMER-NUMBER");
    }

    public static NewCustomerNumberRequest getNew() {
        return new NewCustomerNumberRequest(CounterFunction.GETNEW, null);
    }

    public static NewCustomerNumberRequest current() {
        return new NewCustomerNumberRequest(CounterFunction.CURRENT, null);
    }

    public static NewCustomerNumberRequest rollback(String customerNumber) {
        return new NewCustomerNumberRequest(CounterFunction.ROLLBACK, customerNumber);
    }
}
