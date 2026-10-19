package com.ibm.bankofz.ims.jmp;

import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.Numval;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

/**
 * IBTRAN (deposit/withdraw), paragraph for paragraph with {@code IBTRAN.cbl}; PSB IBTRAN unchanged.
 * Order kept: Db2 history (InsertHist) first, then IMS HISTORY ISRT, then ACCOUNT REPL. Legacy has no ROLB:
 * a failed HISTORY ISRT ends the program after still REPLing ACCOUNT and replying. {@code strict} (off by
 * default, i.e. parity) instead issues ROLB and skips the REPL.
 */
public final class Ibtran {
    static final int FIXED = 48;
    static final int SUMMARY = 17;
    static final int MAX_ACCOUNTS = 6;
    static final int ACCOUNT_SEG = 25;
    static final int HISTORY_SEG = 64;
    static final String INVALID_TRXTYPE = "INVALID ACCOUNT ACTION. MUST BE 'w' OR 'd'.";
    private static final BigDecimal MULT_FACTOR = new BigDecimal("10000000000");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SS");
    private final Dli dli;
    private final PrintStream sysout;
    private final HistorySink history;
    private final Clock clock;
    private final boolean strict;
    private final byte[] input = new byte[56];
    private boolean termIo;

    public Ibtran(Dli dli, PrintStream sysout, HistorySink history, Clock clock, boolean strict) {
        this.dli = dli;
        this.sysout = sysout;
        this.history = history;
        this.clock = clock;
        this.strict = strict;
    }

    public static void main(String[] args) throws Exception {
        new Ibtran(new UdbDli("IBTRAN"), System.out, HistorySink.insertHist(), Clock.systemDefaultZone(),
                Boolean.getBoolean("bankz.ibtran.strict")).run();
    }

    public void run() throws Exception {
        Mpp.loop(dli, sysout, input, () -> {
            byte[] out = accountActivity();
            return Mpp.llzz(out, FIXED + SUMMARY * (out[47] - (byte) 0xF0));
        }, () -> termIo);
    }

    byte[] accountActivity() throws Exception {
        byte[] out = new byte[FIXED + SUMMARY * MAX_ACCOUNTS];
        Arrays.fill(out, (byte) 0xF0);
        String trx = Text.decode(input, 46, 1, Ebcdic.CP1047);
        if (!"dwDW".contains(trx)) {
            msg(out, INVALID_TRXTYPE);
            return out;
        }
        BigDecimal accid = Assign.fit(Numval.of(Text.decode(input, 12, 18, Ebcdic.CP1047)), 18, 0, true, false);
        byte[] key = new byte[8];
        Binary.encode(accid, key, 0, 18, 0, true, true);
        byte[] acc = new byte[ACCOUNT_SEG];
        String dbstat = dli.call("GHU", "ACCOUNT", acc, new Ssa("ACCOUNT", "ACCID", 0, key));
        if (!dbstat.equals("  ")) {
            msg(out, Mpp.notFound(dbstat) ? "ACCOUNT DOES NOT EXIST" : Ibacsum.BAD_STATUS + dbstat);
            return out;
        }
        BigDecimal lasttxid = Binary.decode(acc, 17, 18, 0, true);
        byte[] hist = new byte[HISTORY_SEG];
        Binary.encode(accid.multiply(MULT_FACTOR).add(lasttxid).add(BigDecimal.ONE), hist, 0, 18, 0, true, true);
        Text.encode(LocalDateTime.now(clock).format(TS) + "0", hist, 8, 23, Ebcdic.CP1047);
        Text.encode(trx, hist, 31, 1, Ebcdic.CP1047);
        BigDecimal amount = Assign.fit(Numval.of(Text.decode(input, 30, 16, Ebcdic.CP1047)), 15, 2, true, false);
        PackedDecimal.encode(amount, hist, 32, 15, 2, true);
        Binary.encode(BigDecimal.ZERO, hist, 40, 18, 0, true, true);
        Binary.encode(accid, hist, 48, 18, 0, true, true);
        Binary.encode(lasttxid.add(BigDecimal.ONE), acc, 17, 18, 0, true, true);
        BigDecimal balance = PackedDecimal.decode(acc, 9, 15, 2);
        balance = Assign.fit(trx.equalsIgnoreCase("w") ? balance.subtract(amount) : balance.add(amount), 15, 2, true, false);
        PackedDecimal.encode(balance, acc, 9, 15, 2, true);
        System.arraycopy(acc, 9, hist, 56, 8);
        history.save(hist.clone());
        dbstat = dli.insert("HISTORY", "HISTORY", hist);
        if (!dbstat.equals("  ")) {
            termIo = true;
            if (strict) {
                dli.rollback();
                msg(out, Ibacsum.BAD_STATUS + dbstat);
                return out;
            }
        }
        dli.replace("ACCOUNT", "ACCOUNT", acc);
        // COMPUTE BALANCE-ZONED = BALANCE-ACC + 0; MOVE BALANCE-ZONED TO MSG-OUT (overwrites any BAD-STATUS).
        msg(out, zoned(balance));
        getAccountSummary(out);
        return out;
    }

    /** PIC Z(13).99: unsigned, high-order digits beyond 13 dropped, leading zeros blanked. */
    static String zoned(BigDecimal v) {
        BigDecimal a = v.abs().setScale(2, RoundingMode.DOWN).remainder(BigDecimal.TEN.pow(13));
        String digits = String.format("%016.2f", a);
        String intPart = digits.substring(0, 13).replaceFirst("^0+", "");
        return " ".repeat(13 - intPart.length()) + intPart + digits.substring(13);
    }

    private void getAccountSummary(byte[] out) throws Exception {
        byte[] ca = new byte[16];
        BigDecimal custid = Numval.of(Text.decode(input, 47, 9, Ebcdic.CP1047));
        byte[] caKey = new byte[4];
        Binary.encode(custid, caKey, 0, 9, 0, true, true);
        int total = 0;
        String call = "GHU";
        while (true) {
            String st = dli.call(call, "CUSTACCS", ca, new Ssa("CUSTACCS", "CUSTID", 0, caKey));
            call = "GHN";
            if (!st.equals("  ")) {
                summaryEnd(out, st, total, "CUSTOMER DOES NOT EXIST");
                break;
            }
            byte[] acc = new byte[ACCOUNT_SEG];
            st = dli.call("GHU", "ACCOUNT", acc, new Ssa("ACCOUNT", "ACCID", 0, Arrays.copyOfRange(ca, 4, 12)));
            if (!st.equals("  ")) {
                summaryEnd(out, st, total, "ACCOUNT DOES NOT EXIST");
                break;
            }
            if (total == MAX_ACCOUNTS) {
                throw new IllegalStateException("IBTRAN: more than " + MAX_ACCOUNTS + " accounts");
            }
            int at = FIXED + SUMMARY * total++;
            System.arraycopy(acc, 9, out, at, 8);
            out[at + 8] = acc[8];
            System.arraycopy(acc, 0, out, at + 9, 8);
        }
        out[47] = (byte) (0xF0 + total);
    }

    private static void summaryEnd(byte[] out, String st, int total, String notFound) {
        if (!Mpp.notFound(st)) {
            msg(out, Ibacsum.BAD_STATUS + st);
        } else if (total < 1) {
            msg(out, notFound);
        }
    }

    private static void msg(byte[] out, String text) {
        Text.encode(text, out, 4, 43, Ebcdic.CP1047);
    }
}
