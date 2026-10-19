package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Numval;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.Arrays;

/**
 * IBACSUM (account summary for a customer) as a JMP program, paragraph for paragraph with
 * {@code src/base/ims/cobol/IBACSUM.cbl}, PSB IBACSUM unchanged (PCBs ACCOUNT, CUSTACCS).
 * The output message is the COBOL OUTPUT-AREA byte for byte, which is also the z/OS Connect
 * IBACSUM response contract.
 */
public final class Ibacsum {
    static final int INPUT_LENGTH = 21;
    static final int FIXED = 37;
    static final int SUMMARY = 17;
    static final int MAX_ACCOUNTS = 6;
    static final int OUTPUT_MAX = FIXED + SUMMARY * MAX_ACCOUNTS;
    static final String NOCUSTOMER = "CUSTOMER DOES NOT EXIST";
    static final String NOACCOUNT = "ACCOUNT DOES NOT EXIST";
    static final String BAD_STATUS = "BAD STATUS CODE WAS RECEIVED: ";

    private final Dli dli;
    private final PrintStream sysout;
    /** INPUT-AREA keeps the tail of a longer earlier message when a shorter one arrives. */
    private final byte[] input = new byte[INPUT_LENGTH];

    public Ibacsum(Dli dli, PrintStream sysout) {
        this.dli = dli;
        this.sysout = sysout;
    }

    public static void main(String[] args) throws Exception {
        new Ibacsum(new UdbDli("IBACSUM"), System.out).run();
    }

    public void run() throws Exception {
        sysout.println("IBACSUM ENTERS.... 7/30 3:02 AM ");
        Mpp.loop(dli, sysout, input, () -> {
            byte[] out = getAccountSummary();
            return Mpp.llzz(out, FIXED + SUMMARY * (out[36] - (byte) 0xF0));
        });
    }

    byte[] getAccountSummary() throws Exception {
        byte[] out = new byte[OUTPUT_MAX];
        Arrays.fill(out, (byte) 0xF0);
        BigDecimal custid = Numval.of(Text.decode(input, 12, 9, Ebcdic.CP1047));
        byte[] key = new byte[4];
        Binary.encode(custid, key, 0, 9, 0, true, true);
        int total = 0;
        String next = "GHU";
        String msg = null;
        boolean done = false;
        do {
            byte[] custaccs = new byte[16];
            String dbstat = dli.call(next, "CUSTACCS", custaccs, new Ssa("CUSTACCS", "CUSTID", 0, key));
            if (dbstat.equals("  ")) {
                byte[] account = new byte[33];
                dbstat = dli.call("GHU", "ACCOUNT", account,
                        new Ssa("ACCOUNT", "ACCID", 0, Arrays.copyOfRange(custaccs, 4, 12)));
                if (dbstat.equals("  ")) {
                    if (total == MAX_ACCOUNTS) {
                        // The COBOL subscript would run past OUTPUT-AREA into other storage.
                        throw new IllegalStateException("IBACSUM: customer " + custid + " has more than "
                                + MAX_ACCOUNTS + " accounts");
                    }
                    total++;
                    out[36] = (byte) (0xF0 + total);
                    int o = FIXED + SUMMARY * (total - 1);
                    System.arraycopy(account, 9, out, o, 8);
                    out[o + 8] = account[8];
                    System.arraycopy(account, 0, out, o + 9, 8);
                } else {
                    done = true;
                    msg = notFound(dbstat, total, NOACCOUNT);
                }
            } else {
                done = true;
                msg = notFound(dbstat, total, NOCUSTOMER);
            }
            next = "GHN";
        } while (!done);
        if (msg != null) {
            Text.encode(msg, out, 4, 32, Ebcdic.CP1047);
        }
        return out;
    }

    private static String notFound(String dbstat, int total, String none) {
        if (dbstat.equals("GB") || dbstat.equals("GE")) {
            return total < 1 ? none : null;
        }
        return BAD_STATUS + dbstat;
    }
}
