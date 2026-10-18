package com.ibm.bankofz.core.mapper;

import com.ibm.bankofz.core.codec.Ebcdic;
import com.ibm.bankofz.core.codec.ZonedDecimal;

import java.math.BigDecimal;

/** Sequential writer for DISPLAY-usage records; the EBCDIC serialization boundary. */
final class RecordWriter {

    private final byte[] buf;
    private final Ebcdic text;
    private int pos;

    RecordWriter(int length, Ebcdic text) {
        this.buf = new byte[length];
        this.text = text;
    }

    RecordWriter x(String value, int len) {
        return put(text.encodeExact(value, len));
    }

    RecordWriter n(long value, int digits) {
        return put(ZonedDecimal.encode(BigDecimal.valueOf(value), digits, 0, false));
    }

    RecordWriter n(String digitsText, int digits) {
        return n(digitsText == null || digitsText.isBlank() ? 0 : Long.parseLong(digitsText.trim()), digits);
    }

    RecordWriter s(BigDecimal value, int digits, int scale) {
        return put(ZonedDecimal.encode(value, digits, scale, true));
    }

    RecordWriter u(BigDecimal value, int digits, int scale) {
        return put(ZonedDecimal.encode(value, digits, scale, false));
    }

    byte[] done() {
        if (pos != buf.length) {
            throw new IllegalStateException("wrote " + pos + " of " + buf.length + " bytes");
        }
        return buf;
    }

    private RecordWriter put(byte[] b) {
        System.arraycopy(b, 0, buf, pos, b.length);
        pos += b.length;
        return this;
    }
}
