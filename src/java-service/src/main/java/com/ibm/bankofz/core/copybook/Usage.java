package com.ibm.bankofz.core.copybook;

/** COBOL USAGE clauses that appear in the Bank of Z copybooks. */
public enum Usage {
    DISPLAY,
    /** COMP / COMP-4 / BINARY: truncated to PIC digits. */
    BINARY,
    /** COMP-5: native binary range. */
    NATIVE_BINARY,
    /** COMP-3 / PACKED-DECIMAL. */
    PACKED,
    POINTER
}
