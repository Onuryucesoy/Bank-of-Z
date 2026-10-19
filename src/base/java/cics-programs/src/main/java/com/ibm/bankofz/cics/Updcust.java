package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.DriverManager;

/** CICS program UPDCUST. The default connection joins the task's unit of work; it is not committed here. */
public final class Updcust {
    private Updcust() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        UpdateCustomer.run(holder.getValue(), DriverManager.getConnection("jdbc:default:connection"));
    }
}
