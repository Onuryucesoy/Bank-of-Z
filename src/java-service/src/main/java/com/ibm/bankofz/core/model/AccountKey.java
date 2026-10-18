package com.ibm.bankofz.core.model;

/** Primary key of a CICS account: ACCOUNT-SORT-CODE 9(6) + ACCOUNT-NUMBER 9(8). */
public record AccountKey(String sortCode, long accountNumber) {

    public AccountKey {
        SortCode.requireValid(sortCode);
        if (accountNumber < 0 || accountNumber > 99_999_999L) {
            throw new IllegalArgumentException("account number exceeds PIC 9(8): " + accountNumber);
        }
    }

    public String accountNumberText() {
        return String.format("%08d", accountNumber);
    }
}
