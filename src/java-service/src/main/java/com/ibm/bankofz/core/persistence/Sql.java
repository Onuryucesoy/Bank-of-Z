package com.ibm.bankofz.core.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** JDBC helpers shared by the BANKZ repositories. */
final class Sql {

    private Sql() {
    }

    /** Db2 CHAR columns come back blank-padded; models hold trimmed values. */
    static String chr(ResultSet rs, String col) throws SQLException {
        String s = rs.getString(col);
        return s == null ? null : s.stripTrailing();
    }

    static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    static Date date(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    static String requireIdentifier(String schema) {
        return SqlIdentifier.require(schema);
    }
}
