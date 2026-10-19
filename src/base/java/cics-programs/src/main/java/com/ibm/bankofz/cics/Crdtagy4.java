package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

/** CICS program CRDTAGY4 (container CIPD). */
public final class Crdtagy4 {
    private Crdtagy4() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        JcicsCreditAgency.main("CIPD");
    }
}
