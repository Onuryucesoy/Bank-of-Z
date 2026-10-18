package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.codec.CodecException;
import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.codec.ZonedDecimal;

import java.math.BigDecimal;

/** Sequential reader for DISPLAY-usage records. */
final class RecordReader {

    private final byte[] buf;
    private final Ebcdic text;
    private int pos;

    RecordReader(byte[] buf, int expectedLength, Ebcdic text) {
        if (buf.length != expectedLength) {
            throw new CodecException("expected " + expectedLength + " bytes, got " + buf.length);
        }
        this.buf = buf;
        this.text = text;
    }

    String x(int len) {
        String s = Ebcdic.rtrim(text.decode(buf, pos, len));
        pos += len;
        return s;
    }

    long n(int digits) {
        long v = ZonedDecimal.decode(buf, pos, digits, 0).longValueExact();
        pos += digits;
        return v;
    }

    BigDecimal d(int digits, int scale) {
        BigDecimal v = ZonedDecimal.decode(buf, pos, digits, scale);
        pos += digits;
        return v;
    }
}
