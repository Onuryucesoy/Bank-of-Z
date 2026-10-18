package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.ProcTran;

/** {@link ProcTran} &lt;-&gt; 99-byte EBCDIC PROCTRAN.cpy image. */
public final class ProcTranRecordCodec {

    public static final int LENGTH = 99;

    private final Ebcdic text;

    public ProcTranRecordCodec(Ebcdic text) {
        this.text = text;
    }

    public ProcTranRecordCodec() {
        this(Ebcdic.cp037());
    }

    public byte[] encode(ProcTran p) {
        return new RecordWriter(LENGTH, text)
                .x(p.eyecatcher(), 4).n(p.sortCode(), 6).n(p.accountNumber(), 8)
                .n(p.date() == null ? 0 : p.date().toYyyyMmDd(), 8).n(p.time(), 6).n(p.reference(), 12)
                .x(p.type(), 3).x(p.description(), 40).s(p.amount(), 12, 2)
                .done();
    }

    public ProcTran decode(byte[] b) {
        RecordReader r = new RecordReader(b, LENGTH, text);
        return new ProcTran(r.x(4), String.format("%06d", r.n(6)), r.n(8), CobolDate.ofYyyyMmDd(r.n(8)),
                (int) r.n(6), r.n(12), r.x(3), r.x(40), r.d(12, 2));
    }
}
