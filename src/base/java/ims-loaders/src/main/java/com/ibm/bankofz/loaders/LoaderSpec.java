package com.ibm.bankofz.loaders;

import com.ibm.bankofz.bridge.runtime.Assign;
import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.Text;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * One LOAD* program: its input DD, the UNSTRING receivers (TXT-* fields, in INTO order), the PCB it
 * inserts through and the segment it builds (fields in storage order, each from one TXT-* field).
 */
record LoaderSpec(String program, String inputDd, String pcb, String segment,
                  List<Txt> receivers, List<Seg> fields) {

    static final int RECORD_LENGTH = 200;

    record Txt(String name, int size) {
    }

    enum Kind { CHAR, COMP5, COMP3 }

    /** {@code digits}/{@code scale} for numeric kinds; {@code digits} is the byte length for CHAR. */
    record Seg(String name, Kind kind, int digits, int scale, String from) {
        int length() {
            return switch (kind) {
                case CHAR -> digits;
                case COMP5 -> Binary.byteLength(digits);
                case COMP3 -> PackedDecimal.byteLength(digits);
            };
        }
    }

    int segmentLength() {
        return fields.stream().mapToInt(Seg::length).sum();
    }

    /** SSA "SEGNAME  " as the COBOL program passes it: 8-byte name plus a blank (unqualified). */
    String ssa() {
        return Unstring.move(segment, 8) + " ";
    }

    /** UNSTRING into the (stateful) receivers, then MOVE/COMPUTE them into a fresh segment I/O area. */
    byte[] build(String record, String[] state) {
        int[] sizes = receivers.stream().mapToInt(Txt::size).toArray();
        Unstring.into(Unstring.move(record, RECORD_LENGTH), Unstring.LOADER_DELIMITERS, state, sizes);
        Map<String, Integer> index = new java.util.HashMap<>();
        for (int i = 0; i < receivers.size(); i++) {
            index.put(receivers.get(i).name(), i);
        }
        byte[] seg = new byte[segmentLength()];
        int off = 0;
        for (Seg f : fields) {
            String src = state[index.get(f.from())];
            switch (f.kind()) {
                case CHAR -> Text.encode(Unstring.move(src, f.digits()), seg, off, f.digits(), Ebcdic.CP1047);
                case COMP5 -> Binary.encode(Numval.of(src), seg, off, f.digits(), 0, true, true);
                case COMP3 -> {
                    BigDecimal v = Assign.fit(Numval.of(src), f.digits(), f.scale(), true, false);
                    PackedDecimal.encode(v, seg, off, f.digits(), f.scale(), true);
                }
            }
            off += f.length();
        }
        return seg;
    }

    String[] initialState() {
        return receivers.stream().map(t -> " ".repeat(t.size())).toArray(String[]::new);
    }
}
