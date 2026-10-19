package com.ibm.bankofz.cics;

import com.ibm.cics.server.CommAreaHolder;

/** CICS program CRDTAGY3 (container CIPC). */
public final class Crdtagy3 {
    private Crdtagy3() {
    }

    public static void main(CommAreaHolder holder) throws Exception {
        JcicsCreditAgency.main("CIPC");
    }
}
