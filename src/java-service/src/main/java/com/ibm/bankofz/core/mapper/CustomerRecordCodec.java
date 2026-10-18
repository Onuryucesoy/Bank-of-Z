package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.model.CobolDate;
import com.ibm.bankofz.core.model.Customer;

/** {@link Customer} &lt;-&gt; 397-byte EBCDIC CUSTOMER.cpy image. */
public final class CustomerRecordCodec {

    public static final int LENGTH = 397;

    private final Ebcdic text;

    public CustomerRecordCodec(Ebcdic text) {
        this.text = text;
    }

    public CustomerRecordCodec() {
        this(Ebcdic.cp037());
    }

    public byte[] encode(Customer c) {
        RecordWriter w = new RecordWriter(LENGTH, text)
                .x(c.eyecatcher(), 4).n(c.sortCode(), 6).n(c.customerNumber(), 10)
                .x(c.title(), 10).x(c.firstName(), 50).x(c.lastName(), 50);
        date(w, c.dateOfBirth());
        w.x(c.phone(), 20).x(c.addressLine1(), 50).x(c.addressLine2(), 50).x(c.city(), 50)
                .x(c.postcode(), 10).x(c.country(), 50).x(c.status(), 10);
        date(w, c.created());
        w.n(c.creditScore(), 3);
        date(w, c.creditScoreReviewDate());
        return w.done();
    }

    public Customer decode(byte[] b) {
        RecordReader r = new RecordReader(b, LENGTH, text);
        return new Customer(r.x(4), String.format("%06d", r.n(6)), r.n(10), r.x(10), r.x(50), r.x(50),
                date(r), r.x(20), r.x(50), r.x(50), r.x(50), r.x(10), r.x(50), r.x(10),
                date(r), (int) r.n(3), date(r));
    }

    private static void date(RecordWriter w, CobolDate d) {
        CobolDate v = d == null ? CobolDate.ZERO : d;
        w.n(v.day(), 2).n(v.month(), 2).n(v.year(), 4);
    }

    private static CobolDate date(RecordReader r) {
        return new CobolDate((int) r.n(2), (int) r.n(2), (int) r.n(4));
    }
}
