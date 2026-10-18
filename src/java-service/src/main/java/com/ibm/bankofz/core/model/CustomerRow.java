package com.ibm.bankofz.core.model;

/**
 * BANKZ.CUSTOMER row (CUSTDB2.cpy declaration, CRECUST/DELCUS host
 * variables). Keys are CHAR, dates are INTEGER YYYYMMDD, score SMALLINT.
 * Strings hold the Db2 CHAR value right-trimmed.
 */
public record CustomerRow(
        String eyecatcher,
        String sortCode,
        String customerNumber,
        String title,
        String firstName,
        String lastName,
        Integer dateOfBirth,
        String phone,
        String addressLine1,
        String addressLine2,
        String city,
        String postcode,
        String country,
        String status,
        Integer createdDate,
        Short creditScore,
        Integer creditScoreReviewDate) {
}
