package com.ibm.bankofz.core.model;

/** SORTCODE.cpy: the single bank sort code, {@code PIC 9(6) VALUE 987654}. */
public final class SortCode {

    public static final String BANK = "987654";

    private SortCode() {
    }

    static void requireValid(String sortCode) {
        if (sortCode == null || !sortCode.matches("\\d{6}")) {
            throw new IllegalArgumentException("sort code must be 6 digits: " + sortCode);
        }
    }
}
