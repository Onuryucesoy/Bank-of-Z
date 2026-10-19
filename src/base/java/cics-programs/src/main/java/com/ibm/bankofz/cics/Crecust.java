package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** CICS program CRECUST. */
public final class Crecust {
    private Crecust() {
    }

    public static void main(CommAreaHolder holder) throws SQLException {
        try (Connection con = DriverManager.getConnection("jdbc:default:connection")) {
            CreateCustomer.run(holder.getValue(), con, CicsTask.jcics(), new JcicsCreditCheck());
        }
    }
}
