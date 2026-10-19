package com.ibm.bankofz.bridge.runtime;

import java.nio.charset.Charset;
import java.util.Arrays;

/** PIC X / PIC A codec: fixed length, right padded with spaces, truncated on the right (COBOL MOVE rules). */
public final class Text {
    private Text() {
    }

    public static String decode(byte[] buf, int offset, int length, Charset cs) {
        return new String(buf, offset, length, cs);
    }

    public static void encode(String value, byte[] buf, int offset, int length, Charset cs) {
        byte[] bytes = value.getBytes(cs);
        int n = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buf, offset, n);
        Arrays.fill(buf, offset + n, offset + length, Ebcdic.space(cs));
    }

    public static void fill(byte[] buf, int offset, int length, byte b) {
        Arrays.fill(buf, offset, offset + length, b);
    }
}
