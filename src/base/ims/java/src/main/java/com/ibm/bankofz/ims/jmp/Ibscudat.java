package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * IBSCUDAT (set customer data), paragraph for paragraph with {@code IBSCUDAT.cbl}; PSB IBSCUDAT unchanged.
 * CUSTID arrives binary (S9(9) COMP-5) in CUSTOMER-IN, not as digits; the reply echoes CUSTOMER-IN.
 */
public final class Ibscudat {
    static final int CUSTOMER_IN = 238;
    static final int INPUT = 12 + CUSTOMER_IN;
    static final int OUTPUT = 36 + CUSTOMER_IN;
    private final Dli dli;
    private final PrintStream sysout;
    private final byte[] input = new byte[INPUT];

    public Ibscudat(Dli dli, PrintStream sysout) {
        this.dli = dli;
        this.sysout = sysout;
    }

    public static void main(String[] args) throws Exception {
        new Ibscudat(new UdbDli("IBSCUDAT"), System.out).run();
    }

    public void run() throws Exception {
        Mpp.loop(dli, sysout, input, () -> Mpp.llzz(setCustomerData(), OUTPUT));
    }

    byte[] setCustomerData() throws Exception {
        byte[] out = new byte[OUTPUT];
        Arrays.fill(out, (byte) 0xF0);
        byte[] seg = new byte[Customer.LENGTH];
        String dbstat = dli.call("GHU", "CUSTOMER", seg,
                new Ssa("CUSTOMER", "CUSTID", Customer.CUSTID, Arrays.copyOfRange(input, 12, 16)));
        String msg = null;
        if (dbstat.equals("  ")) {
            // FIRSTNAME..PHONE: same order and lengths in CUSTOMER-IN and the segment, except names swap.
            System.arraycopy(input, 12 + 54, seg, Customer.FIRSTNAME, 50);
            System.arraycopy(input, 12 + 4, seg, Customer.LASTNAME, 50);
            System.arraycopy(input, 12 + 104, seg, Customer.ADDRESS, Customer.STATUS - Customer.ADDRESS);
            dbstat = dli.replace("CUSTOMER", "CUSTOMER", seg);
            if (dbstat.equals("  ")) {
                System.arraycopy(input, 12, out, 36, CUSTOMER_IN);
            } else {
                msg = "UNABLE TO UPDATE CUSTOMER: " + dbstat;
            }
        } else {
            msg = Mpp.notFound(dbstat) ? "CUSTOMER DOES NOT EXIST" : Ibacsum.BAD_STATUS + dbstat;
        }
        if (msg != null) {
            Text.encode(msg, out, 4, 32, Ebcdic.CP1047);
        }
        return out;
    }
}
