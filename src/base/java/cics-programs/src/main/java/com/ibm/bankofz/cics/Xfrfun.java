package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** CICS program XFRFUN. */
public final class Xfrfun {
    private Xfrfun() {
    }

    public static void main(CommAreaHolder holder) throws SQLException {
        try (Connection con = DriverManager.getConnection("jdbc:default:connection")) {
            TransferFunds.run(holder.getValue(), con, CicsTask.jcics());
        }
    }
}
