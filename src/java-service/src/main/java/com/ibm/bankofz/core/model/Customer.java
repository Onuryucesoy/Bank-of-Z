package com.ibm.bankofz.core.model;

/**
 * CUSTOMER.cpy (src/base/cics/copy) — 397-byte customer record, also the
 * layout of the CRDTAGY1-5 credit-check containers (CIPA..CIPE). Text fields
 * are right-trimmed; widths are enforced by {@link com.ibm.bankofz.core.mapper.CustomerRecordCodec}.
 */
public record Customer(
        String eyecatcher,
        String sortCode,
        long customerNumber,
        String title,
        String firstName,
        String lastName,
        CobolDate dateOfBirth,
        String phone,
        String addressLine1,
        String addressLine2,
        String city,
        String postcode,
        String country,
        String status,
        CobolDate created,
        int creditScore,
        CobolDate creditScoreReviewDate) {

    public static final String EYECATCHER = "CUST";
    public static final int MAX_CUSTOMER_NUMBER_DIGITS = 10;

    /** CUSTOMER-STATUS 88-levels. */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    public CustomerKey key() {
        return new CustomerKey(sortCode, customerNumber);
    }
}
