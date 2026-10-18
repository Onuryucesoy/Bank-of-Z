package com.ibm.bankofz.migration.codec;

/** Raised when bytes are not valid for the PIC clause, or a Java value cannot be stored without loss. */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
