package com.ibm.bankofz.bridge.runtime;

import java.nio.charset.Charset;

/** Code pages used by Bank of Z: CICS/Db2 data is CP037, IMS DBDs declare ENCODING=Cp1047. */
public final class Ebcdic {
    public static final Charset CP037 = Charset.forName("IBM037");
    public static final Charset CP1047 = Charset.forName("IBM1047");

    private Ebcdic() {
    }

    public static byte space(Charset cs) {
        return " ".getBytes(cs)[0];
    }
}
