package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.Connection;
import java.sql.DriverManager;

/** CICS program UPDACC. The default connection joins the task's unit of work; it is not committed here. */
public final class Updacc {
    private Updacc() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        Connection con = DriverManager.getConnection("jdbc:default:connection");
        UpdateAccount.run(holder.getValue(), con);
    }
}
