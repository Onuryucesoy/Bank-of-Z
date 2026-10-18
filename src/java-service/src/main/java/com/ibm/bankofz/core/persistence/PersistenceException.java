package com.ibm.bankofz.core.persistence;

import java.sql.SQLException;

/** Unchecked wrapper exposing the Db2 SQLCODE the COBOL programs branch on (e.g. -911, 100). */
public class PersistenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int sqlCode;

    public PersistenceException(String sql, SQLException cause) {
        super(cause.getMessage() + " [" + sql + "]", cause);
        this.sqlCode = cause.getErrorCode();
    }

    public int sqlCode() {
        return sqlCode;
    }
}
