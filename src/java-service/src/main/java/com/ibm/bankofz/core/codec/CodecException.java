package com.ibm.bankofz.core.codec;

/** Raised when a value cannot be represented in, or decoded from, a COBOL field. */
public class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
