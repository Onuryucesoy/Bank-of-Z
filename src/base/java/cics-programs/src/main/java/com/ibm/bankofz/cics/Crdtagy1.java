package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

/** CICS program CRDTAGY1 (container CIPA). */
public final class Crdtagy1 {
    private Crdtagy1() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        JcicsCreditAgency.main("CIPA");
    }
}
