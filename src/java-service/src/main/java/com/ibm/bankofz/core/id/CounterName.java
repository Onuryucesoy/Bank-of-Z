package com.ibm.bankofz.core.id;

import java.util.Objects;

/**
 * Identifies a counter the way CRECUST/CREACC do: a prefix ({@code BANKZCUST}
 * or {@code BANKZACCT}) plus the sort code, padded to the CHAR(32)
 * CONTROL_NAME. {@code maxDigits} is the PIC width of the allocated number.
 */
public record CounterName(String prefix, String sortCode, int maxDigits) {

    public static final String CUSTOMER_PREFIX = "BANKZCUST";
    public static final String ACCOUNT_PREFIX = "BANKZACCT";

    public CounterName {
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(sortCode);
        if (maxDigits < 1 || maxDigits > 18) {
            throw new IllegalArgumentException("maxDigits out of range: " + maxDigits);
        }
    }

    /** Customer numbers: CUSTOMER-NUMBER PIC 9(10). */
    public static CounterName customer(String sortCode) {
        return new CounterName(CUSTOMER_PREFIX, sortCode, 10);
    }

    /** Account numbers: ACCOUNT-NUMBER PIC 9(8). */
    public static CounterName account(String sortCode) {
        return new CounterName(ACCOUNT_PREFIX, sortCode, 8);
    }

    /** CONTROL_NAME key value, e.g. {@code BANKZCUST987654}. Db2 CHAR comparison ignores trailing blanks. */
    public String controlName() {
        return prefix + sortCode;
    }

    public long maxValue() {
        return (long) Math.pow(10, maxDigits) - 1;
    }
}
