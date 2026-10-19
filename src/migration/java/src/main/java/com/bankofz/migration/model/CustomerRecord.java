package com.bankofz.migration.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * {@code 03 CUSTOMER-RECORD} of src/base/cics/copy/CUSTOMER.cpy, persisted as the
 * Db2 table declared in CUSTDB2.cpy (src/migration/db2/V001__target_schema.sql).
 *
 * <p>Keys ({@code PIC 9(6)} sort code, {@code PIC 9(10)} number) are kept as
 * zero-padded digit strings so they round-trip byte-for-byte with the
 * {@code CHAR(6)}/{@code CHAR(10)} key columns.
 *
 * <p>Dates: the three date components are {@code DD/MM/YYYY} groups in the
 * record layout ({@link CobolDates#fromDdMmYyyy(String)}) but Db2
 * {@code INTEGER YYYYMMDD} in the table ({@link CobolDates#fromDb2Integer(int)}).
 * {@code null} represents the COBOL zero date.
 *
 * <p>OpenAPI {@code Customer}: sortCode/customerNumber form the path id, title,
 * firstName, lastName, dateOfBirth, phone, address, customerStatus, creditScore
 * map by name; {@code createdDate} is a date-time in the API but only a date here.
 */
public record CustomerRecord(
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-SORTCODE", value = CustomerRecord.PIC_SORTCODE)
        String sortCode,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-NUMBER", value = CustomerRecord.PIC_NUMBER)
        String customerNumber,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-TITLE", value = CustomerRecord.PIC_TITLE)
        String title,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-FIRST-NAME", value = CustomerRecord.PIC_NAME)
        String firstName,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-LAST-NAME", value = CustomerRecord.PIC_NAME)
        String lastName,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-DOB", value = CustomerRecord.PIC_DATE_GROUP)
        LocalDate dateOfBirth,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-PHONE", value = CustomerRecord.PIC_PHONE)
        String phone,
        Address address,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-STATUS", value = CustomerRecord.PIC_STATUS)
        CustomerStatus status,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-CREATED-DATE", value = CustomerRecord.PIC_DATE_GROUP)
        LocalDate createdDate,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-CREDIT-SCORE", value = CustomerRecord.PIC_CREDIT_SCORE)
        int creditScore,
        @Pic(copybook = Copybooks.CUSTOMER, field = "CUSTOMER-CS-REVIEW-DATE", value = CustomerRecord.PIC_DATE_GROUP)
        LocalDate creditScoreReviewDate) {

    /** {@code 88 CUSTOMER-EYECATCHER-VALUE VALUE 'CUST'}. */
    public static final String EYECATCHER = "CUST";
    public static final String PIC_SORTCODE = "9(6)";
    public static final String PIC_NUMBER = "9(10)";
    public static final String PIC_TITLE = "X(10)";
    public static final String PIC_NAME = "X(50)";
    public static final String PIC_PHONE = "X(20)";
    public static final String PIC_STATUS = "X(10)";
    public static final String PIC_CREDIT_SCORE = "999";
    /** DD (99) + MM (99) + YYYY (9999) group, 8 display digits. */
    public static final String PIC_DATE_GROUP = "9(8)";

    public CustomerRecord {
        sortCode = CobolFields.digits(sortCode, PIC_SORTCODE, "CUSTOMER-SORTCODE");
        customerNumber = CobolFields.digits(customerNumber, PIC_NUMBER, "CUSTOMER-NUMBER");
        title = CobolFields.text(title, PIC_TITLE, "CUSTOMER-TITLE");
        firstName = CobolFields.text(firstName, PIC_NAME, "CUSTOMER-FIRST-NAME");
        lastName = CobolFields.text(lastName, PIC_NAME, "CUSTOMER-LAST-NAME");
        phone = CobolFields.text(phone, PIC_PHONE, "CUSTOMER-PHONE");
        Objects.requireNonNull(address, "CUSTOMER-ADDRESS");
        Objects.requireNonNull(status, "CUSTOMER-STATUS");
        CobolFields.integer(creditScore, PIC_CREDIT_SCORE, "CUSTOMER-CREDIT-SCORE");
        // Fail fast on dates that cannot be written back into PIC 9999 years.
        CobolDates.toDdMmYyyy(dateOfBirth);
        CobolDates.toDdMmYyyy(createdDate);
        CobolDates.toDdMmYyyy(creditScoreReviewDate);
    }
}
