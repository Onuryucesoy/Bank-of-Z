package com.ibm.bankofz.bridge.runtime;

/** Raised where COBOL would take a data exception (S0C7): invalid digit or sign nibble in numeric data. */
public final class CobolDataException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CobolDataException(String message) {
        super(message);
    }
}
