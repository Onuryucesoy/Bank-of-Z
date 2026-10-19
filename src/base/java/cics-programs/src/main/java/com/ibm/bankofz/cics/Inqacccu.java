package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.DriverManager;

/** CICS program INQACCCU. */
public final class Inqacccu {
    private Inqacccu() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        InquireAccountsForCustomer.run(holder.getValue(), DriverManager.getConnection("jdbc:default:connection"),
                CicsTask.jcics());
    }
}
