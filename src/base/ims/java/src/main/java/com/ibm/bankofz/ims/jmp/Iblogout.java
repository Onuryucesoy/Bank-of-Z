package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * IBLOGOUT, paragraph for paragraph with {@code IBLOGOUT.cbl}; PSB IBLOGOUT unchanged.
 * Kept as in COBOL: a failed REPL still answers "LOGOFF SUCCESSFUL" (the BAD-STATUS text is overwritten).
 */
public final class Iblogout {
    static final int OUTPUT = 36;
    private final Dli dli;
    private final PrintStream sysout;
    private final byte[] input = new byte[21];

    public Iblogout(Dli dli, PrintStream sysout) {
        this.dli = dli;
        this.sysout = sysout;
    }

    public static void main(String[] args) throws Exception {
        new Iblogout(new UdbDli("IBLOGOUT"), System.out).run();
    }

    public void run() throws Exception {
        Mpp.loop(dli, sysout, input, () -> Mpp.llzz(logout(), OUTPUT));
    }

    byte[] logout() throws Exception {
        byte[] out = new byte[OUTPUT];
        Arrays.fill(out, (byte) 0xF0);
        byte[] seg = new byte[Customer.LENGTH];
        String dbstat = dli.call("GHU", "CUSTOMER", seg, Customer.ssa(input));
        String msg;
        if (!dbstat.equals("  ")) {
            msg = Mpp.notFound(dbstat) ? "FAILED UPDATE FOR LOGOFF" : Ibacsum.BAD_STATUS + dbstat;
        } else {
            Text.encode("0", seg, Customer.STATUS, 1, Ebcdic.CP1047);
            dli.replace("CUSTOMER", "CUSTOMER", seg);
            msg = "LOGOFF SUCCESSFUL";
        }
        Text.encode(msg, out, 4, 32, Ebcdic.CP1047);
        return out;
    }
}
