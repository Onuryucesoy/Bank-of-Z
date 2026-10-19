package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

/** CICS program CRDTAGY5 (container CIPE). */
public final class Crdtagy5 {
    private Crdtagy5() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        JcicsCreditAgency.main("CIPE");
    }
}
