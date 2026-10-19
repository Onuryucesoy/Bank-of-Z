package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** CICS program CREACC. */
public final class Creacc {
    private Creacc() {
    }

    public static void main(CommAreaHolder holder) throws SQLException {
        try (Connection con = DriverManager.getConnection("jdbc:default:connection")) {
            CreateAccount.run(holder.getValue(), con, CicsTask.jcics());
        }
    }
}
