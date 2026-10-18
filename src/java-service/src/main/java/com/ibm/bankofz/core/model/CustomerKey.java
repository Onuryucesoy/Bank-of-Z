package com.ibm.bankofz.core.model;

/** Primary key of a CICS customer: CUSTOMER-SORTCODE 9(6) + CUSTOMER-NUMBER 9(10). */
public record CustomerKey(String sortCode, long customerNumber) {

    public CustomerKey {
        SortCode.requireValid(sortCode);
        if (customerNumber < 0 || customerNumber > 9_999_999_999L) {
            throw new IllegalArgumentException("customer number exceeds PIC 9(10): " + customerNumber);
        }
    }

    /** Zero-padded CHAR(10) as stored in BANKZ.CUSTOMER.CUSTOMER_NUMBER. */
    public String customerNumberText() {
        return String.format("%010d", customerNumber);
    }
}
