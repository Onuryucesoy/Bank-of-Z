package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.DriverManager;

/** CICS program INQACC. */
public final class Inqacc {
    private Inqacc() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        InquireAccount.run(holder.getValue(), DriverManager.getConnection("jdbc:default:connection"),
                CicsTask.jcics());
    }
}
