package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

import java.sql.DriverManager;

/** CICS program INQCUST. */
public final class Inqcust {
    private Inqcust() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        InquireCustomer.run(holder.getValue(), DriverManager.getConnection("jdbc:default:connection"),
                CicsTask.jcics());
    }
}
