package com.ibm.bankofz.ims.jmp;

import java.io.PrintStream;

/** The message loop every IMS COBOL program here shares: GU the I/O PCB until QC, ISRT one reply per message. */
final class Mpp {
    @FunctionalInterface
    interface Handler {
        /** Returns the OUTPUT-AREA with LL already set. */
        byte[] process() throws Exception;
    }

    private Mpp() {
    }

    static void loop(Dli dli, PrintStream sysout, byte[] input, Handler handler) throws Exception {
        loop(dli, sysout, input, handler, () -> false);
    }

    /** {@code stop} is the COBOL TERM-IO flag set inside the handler: checked before the next GU. */
    static void loop(Dli dli, PrintStream sysout, byte[] input, Handler handler,
                     java.util.function.BooleanSupplier stop) throws Exception {
        while (!stop.getAsBoolean()) {
            String tpstat = dli.getUniqueMessage(input);
            if (tpstat.equals("  ") || tpstat.equals("CF")) {
                byte[] out = handler.process();
                int ll = ((out[0] & 0xFF) << 8) | (out[1] & 0xFF);
                String st = dli.insertMessage(out, ll);
                if (!st.equals("  ")) {
                    sysout.println("INSERT TO IOPCB FAILED WITH STATUS CODE: " + st);
                }
                dli.syncpoint();
            } else if (tpstat.equals("QC")) {
                return;
            } else {
                sysout.println("GU FROM IOPCB FAILED WITH STATUS CODE: " + tpstat);
            }
        }
    }

    /** INSERT-IO: {@code COMPUTE LL-OUT = LENGTH OF OUTPUT-AREA. MOVE 0 TO ZZ-OUT.} */
    static byte[] llzz(byte[] out, int ll) {
        out[0] = (byte) (ll >> 8);
        out[1] = (byte) ll;
        out[2] = 0;
        out[3] = 0;
        return out;
    }

    static boolean notFound(String dbstat) {
        return dbstat.equals("GB") || dbstat.equals("GE");
    }
}
