package com.ibm.bankofz.loaders;

import java.io.PrintStream;
import java.util.HexFormat;
import java.util.Iterator;

/** The PROCEDURE DIVISION shared by the five LOAD* programs. */
final class Loader {
    private Loader() {
    }

    /** Returns the number of records processed (INPUT-RECORDS); stops at the first nonblank status. */
    static int run(LoaderSpec spec, Iterator<String> input, SegmentSink sink, PrintStream sysout) throws Exception {
        sysout.println("DBPCB: " + spec.pcb());
        String[] state = spec.initialState();
        int records = 0;
        boolean done = !input.hasNext();
        if (done) {
            sysout.println("END OF FILE");
        }
        String record = done ? null : input.next();
        while (!done) {
            byte[] seg = spec.build(record, state);
            String status = sink.insert(spec, seg);
            if (!status.isBlank()) {
                sysout.println("BAD STATUS CODE: " + status);
                done = true;
            }
            records++;
            if (input.hasNext()) {
                record = input.next();
            } else {
                sysout.println("END OF FILE");
                done = true;
            }
            if (records % 1000 == 0) {
                sysout.println("INPUT-RECORDS: " + records);
            }
            if (records == 1) {
                sysout.println(spec.segment() + "-SEG >>" + HexFormat.of().withUpperCase().formatHex(seg) + "<<");
            }
        }
        sysout.println("TOTAL INPUT RECORDS: " + records);
        return records;
    }
}
