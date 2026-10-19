package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * IBLOGIN, ported from {@code IBLOGIN.pli} (the program deployed as PGM IBLOGIN; PSB IBLOGIN unchanged).
 * Message bytes on the queue: LL ZZ TRAN(8) CUSTID(9) PASSWD(16) in, LL ZZ MSG(32) out (PL/I's fullword
 * LL_OUT = SIZE-2 yields the same 36-byte segment). Unlike IBLOGIN1.cbl, a failed REPL reports BAD STATUS.
 */
public final class Iblogin {
    static final int OUTPUT = 36;
    private static final DateTimeFormatter LASTLOGIN = DateTimeFormatter.ofPattern("yyyy_MM_dd HH:mm:ss:SS");
    private final Dli dli;
    private final PrintStream sysout;
    private final Clock clock;
    private final byte[] input = new byte[37];

    public Iblogin(Dli dli, PrintStream sysout, Clock clock) {
        this.dli = dli;
        this.sysout = sysout;
        this.clock = clock;
    }

    public static void main(String[] args) throws Exception {
        new Iblogin(new UdbDli("IBLOGIN"), System.out, Clock.systemDefaultZone()).run();
    }

    public void run() throws Exception {
        Mpp.loop(dli, sysout, input, () -> Mpp.llzz(login(), OUTPUT));
    }

    byte[] login() throws Exception {
        byte[] out = new byte[OUTPUT];
        Arrays.fill(out, 4, OUTPUT, (byte) 0xF0);
        byte[] seg = new byte[Customer.LENGTH];
        String dbstat = dli.call("GHU", "CUSTOMER", seg, Customer.ssa(input));
        String msg;
        if (!dbstat.equals("  ")) {
            msg = Mpp.notFound(dbstat) ? "CUSTOMER DOES NOT EXIST" : Ibacsum.BAD_STATUS + dbstat;
        } else if (!Arrays.equals(seg, Customer.PASSWORD, Customer.PASSWORD + 16, input, 21, 37)) {
            msg = "PASSWORD INVALID";
        } else if (Text.decode(seg, Customer.STATUS, 1, Ebcdic.CP1047).equals("1")) {
            msg = "CUSTOMER ALREADY LOGGED IN";
        } else {
            // DATETIME() hundredths of a second, then TIMESTAMP's trailing FILLER INIT('0').
            Text.encode(LocalDateTime.now(clock).format(LASTLOGIN) + "0", seg, Customer.LASTLOGIN, 23, Ebcdic.CP1047);
            Text.encode("1", seg, Customer.STATUS, 1, Ebcdic.CP1047);
            dbstat = dli.replace("CUSTOMER", "CUSTOMER", seg);
            msg = dbstat.equals("  ") ? "LOGIN SUCCESSFUL" : Ibacsum.BAD_STATUS + dbstat;
        }
        Text.encode(msg, out, 4, 32, Ebcdic.CP1047);
        return out;
    }
}
