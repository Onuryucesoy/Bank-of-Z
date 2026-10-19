package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** CICS program DELCUS. */
public final class Delcus {
    private Delcus() {
    }

    public static void main(CommAreaHolder holder) throws SQLException {
        try (Connection con = DriverManager.getConnection("jdbc:default:connection")) {
            DeleteCustomer.run(holder.getValue(), con, CicsTask.jcics());
        }
    }
}
