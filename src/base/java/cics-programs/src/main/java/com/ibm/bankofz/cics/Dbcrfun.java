package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** CICS program DBCRFUN. */
public final class Dbcrfun {
    private Dbcrfun() {
    }

    public static void main(CommAreaHolder holder) throws SQLException {
        try (Connection con = DriverManager.getConnection("jdbc:default:connection")) {
            DebitCredit.run(holder.getValue(), con, CicsTask.jcics());
        }
    }
}
