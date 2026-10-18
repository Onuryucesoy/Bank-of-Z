package com.ibm.bankofz.core.id;

/** Allocation failed; CRECUST reports this as COMM-FAIL-CODE '4' (counter read/update failure). */
public class IdAllocationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IdAllocationException(String message) {
        super(message);
    }

    public IdAllocationException(String message, Throwable cause) {
        super(message, cause);
    }
}
