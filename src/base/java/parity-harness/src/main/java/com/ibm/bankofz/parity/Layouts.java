package com.ibm.bankofz.parity;

import com.ibm.bankofz.bridge.copybook.cics.CicsCopybooks;
import com.ibm.bankofz.bridge.copybook.ims.ImsCopybooks;
import com.ibm.bankofz.bridge.runtime.RecordLayout;

/** Resolves tape layout names ({@code CICS:PAYDBCR}, {@code IMS:IBTRAN/OUTPUT-AREA}) to generated layouts. */
public final class Layouts {
    private Layouts() {
    }

    /** Returns {@code null} for {@code -} (no copybook: compare raw bytes); throws for unknown names. */
    public static RecordLayout resolve(String name) {
        if (name == null || name.equals("-")) {
            return null;
        }
        int colon = name.indexOf(':');
        String tree = colon < 0 ? "" : name.substring(0, colon);
        String key = name.substring(colon + 1);
        RecordLayout layout = switch (tree) {
            case "CICS" -> CicsCopybooks.LAYOUTS.get(key);
            case "IMS" -> ImsCopybooks.LAYOUTS.get(key);
            default -> null;
        };
        if (layout == null) {
            throw new IllegalArgumentException("unknown layout " + name);
        }
        return layout;
    }
}
