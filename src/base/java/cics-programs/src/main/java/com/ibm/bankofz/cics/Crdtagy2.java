package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

/** CICS program CRDTAGY2 (container CIPB). */
public final class Crdtagy2 {
    private Crdtagy2() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        JcicsCreditAgency.main("CIPB");
    }
}
