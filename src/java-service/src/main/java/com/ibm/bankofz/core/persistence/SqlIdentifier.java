package com.ibm.bankofz.core.persistence;

/** Validates schema/object names that have to be concatenated into SQL text. */
public final class SqlIdentifier {

    private SqlIdentifier() {
    }

    public static String require(String name) {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) {
            throw new IllegalArgumentException("invalid SQL identifier: " + name);
        }
        return name;
    }
}
