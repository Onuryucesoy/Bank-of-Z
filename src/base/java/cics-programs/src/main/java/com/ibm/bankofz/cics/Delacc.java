package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.DriverManager;

/** CICS program DELACC. The default connection joins the task's unit of work; it is not committed here. */
public final class Delacc {
    private Delacc() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        DeleteAccount.run(holder.getValue(), DriverManager.getConnection("jdbc:default:connection"),
                CicsTask.jcics());
    }
}
