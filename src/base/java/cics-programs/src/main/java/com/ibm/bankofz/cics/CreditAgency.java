package com.ibm.bankofz.cics;

import com.ibm.bankofz.bridge.copybook.cics.Customer;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.Random;

/**
 * CRDTAGY1-5 (credit agency simulators), paragraph for paragraph. The five COBOL programs differ only in
 * the container name (CIPA..CIPE) on channel CIPCREDCHANN; WS-CONT-IN is the CUSTOMER copybook (397 bytes).
 * The score comes from a PRNG, so parity is checked on layout and range, not value: LE's FUNCTION RANDOM
 * sequence is not reproduced.
 */
public final class CreditAgency {
    static final String CHANNEL = "CIPCREDCHANN";

    /** The CICS commands the program issues. */
    public interface Cics {
        int taskNumber();

        void delay(int seconds) throws Exception;

        /** GET CONTAINER; null when RESP is not NORMAL. */
        byte[] getContainer(String name) throws Exception;

        /** PUT CONTAINER; false when RESP is not NORMAL. */
        boolean putContainer(String name, byte[] data) throws Exception;

        void abend(String code);
    }

    private CreditAgency() {
    }

    public static void run(Cics cics, String container, PrintStream sysout) throws Exception {
        String name = String.format("%-16s", container);
        Random random = new Random(cics.taskNumber());
        // COMPUTE WS-DELAY-AMT = ((3 - 1) * FUNCTION RANDOM(WS-SEED)) + 1  (S9(8) COMP: truncated)
        int delay = (int) (2 * random.nextDouble()) + 1;
        try {
            cics.delay(delay);
        } catch (Exception e) {
            sysout.println("*** The delay messed up ! ***");
            cics.abend("PLOP");
            return;
        }
        byte[] in = cics.getContainer(name.strip());
        if (in == null || in.length > Customer.LENGTH) {
            sysout.println("CRDTAGY - UNABLE TO GET CONTAINER. CONTAINER=" + name + " CHANNEL=" + CHANNEL
                    + " FLENGTH=" + Customer.LENGTH);
            return;
        }
        byte[] cont = Arrays.copyOf(in, Customer.LENGTH);
        // COMPUTE WS-NEW-CREDSCORE = ((999 - 1) * FUNCTION RANDOM) + 1  (PIC 999: truncated)
        int score = (int) (998 * random.nextDouble()) + 1;
        Text.encode(String.format("%03d", score), cont, Customer.CUSTOMER_CREDIT_SCORE.offset(), 3, Ebcdic.CP037);
        if (!cics.putContainer(name.strip(), cont)) {
            sysout.println("CRDTAGY - UNABLE TO PUT CONTAINER. CONTAINER=" + name + " CHANNEL=" + CHANNEL
                    + " FLENGTH=" + Customer.LENGTH);
        }
    }
}
