package com.bankofz.migration.model;

/**
 * {@code CUSTOMER-STATUS PIC X(10)} 88-levels from src/base/cics/copy/CUSTOMER.cpy.
 * Matches the {@code customerStatus} enum of the OpenAPI {@code Customer} schema.
 */
public enum CustomerStatus {
    /** 88 CUSTOMER-STATUS-ACTIVE VALUE 'ACTIVE'. */
    ACTIVE("ACTIVE"),
    /** 88 CUSTOMER-STATUS-INACTIVE VALUE 'INACTIVE'. */
    INACTIVE("INACTIVE"),
    /** 88 CUSTOMER-STATUS-SUSPENDED VALUE 'SUSPENDED'. */
    SUSPENDED("SUSPENDED");

    private final String cobolValue;

    CustomerStatus(String cobolValue) {
        this.cobolValue = cobolValue;
    }

    /** The 88-level VALUE literal (without the blank padding to 10 bytes). */
    public String cobolValue() {
        return cobolValue;
    }

    /** Maps the blank-padded CHAR(10) column value; unknown values are rejected. */
    public static CustomerStatus fromCobol(String value) {
        String v = value == null ? "" : value.stripTrailing();
        for (CustomerStatus s : values()) {
            if (s.cobolValue.equals(v)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Not a CUSTOMER-STATUS 88-level value: '" + value + "'");
    }
}
