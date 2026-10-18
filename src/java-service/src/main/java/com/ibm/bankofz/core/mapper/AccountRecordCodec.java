package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.model.Account;
import com.ibm.bankofz.core.model.CobolDate;

/** {@link Account} &lt;-&gt; 98-byte EBCDIC ACCOUNT.cpy image. */
public final class AccountRecordCodec {

    public static final int LENGTH = 98;

    private final Ebcdic text;

    public AccountRecordCodec(Ebcdic text) {
        this.text = text;
    }

    public AccountRecordCodec() {
        this(Ebcdic.cp037());
    }

    public byte[] encode(Account a) {
        return new RecordWriter(LENGTH, text)
                .x(a.eyecatcher(), 4).n(a.customerNumber(), 10).n(a.sortCode(), 6).n(a.accountNumber(), 8)
                .x(a.accountType(), 8).u(a.interestRate(), 6, 2)
                .n(ddmmyyyy(a.opened()), 8).n(a.overdraftLimit(), 8)
                .n(ddmmyyyy(a.lastStatementDate()), 8).n(ddmmyyyy(a.nextStatementDate()), 8)
                .s(a.availableBalance(), 12, 2).s(a.actualBalance(), 12, 2)
                .done();
    }

    public Account decode(byte[] b) {
        RecordReader r = new RecordReader(b, LENGTH, text);
        return new Account(r.x(4), r.n(10), String.format("%06d", r.n(6)), r.n(8), r.x(8), r.d(6, 2),
                CobolDate.ofDdMmYyyy(r.n(8)), r.n(8), CobolDate.ofDdMmYyyy(r.n(8)),
                CobolDate.ofDdMmYyyy(r.n(8)), r.d(12, 2), r.d(12, 2));
    }

    private static long ddmmyyyy(CobolDate d) {
        return d == null ? 0 : d.toDdMmYyyy();
    }
}
