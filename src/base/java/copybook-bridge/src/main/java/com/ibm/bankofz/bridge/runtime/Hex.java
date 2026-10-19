package com.ibm.bankofz.bridge.runtime;

import java.util.HexFormat;

public final class Hex {
    private static final HexFormat FORMAT = HexFormat.of().withUpperCase();

    private Hex() {
    }

    public static String of(byte[] buf, int offset, int length) {
        return FORMAT.formatHex(buf, offset, offset + length);
    }

    public static String of(byte[] buf) {
        return FORMAT.formatHex(buf);
    }

    public static byte[] parse(String hex) {
        return FORMAT.parseHex(hex.replace(" ", ""));
    }
}
