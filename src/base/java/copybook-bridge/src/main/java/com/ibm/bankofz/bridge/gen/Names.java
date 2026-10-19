package com.ibm.bankofz.bridge.gen;

import java.util.Locale;
import java.util.Set;

final class Names {
    private static final Set<String> RESERVED_PASCAL = Set.of("Class", "Bytes", "Layout");
    private static final Set<String> RESERVED_CONST = Set.of("LENGTH", "LAYOUT", "ODO_TABLE_OFFSET", "ODO_STRIDE",
            "MINIMUM_LENGTH");

    private Names() {
    }

    static String pascal(String cobol) {
        StringBuilder sb = new StringBuilder();
        for (String part : cobol.toLowerCase(Locale.ROOT).split("[-_]")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            s = "F" + s;
        }
        return RESERVED_PASCAL.contains(s) ? s + "Field" : s;
    }

    static String constant(String cobol) {
        String c = cobol.toUpperCase(Locale.ROOT).replaceAll("[-_]+", "_");
        if (!Character.isJavaIdentifierStart(c.charAt(0))) {
            c = "F" + c;
        }
        return RESERVED_CONST.contains(c) ? c + "_FIELD" : c;
    }

    static String javaString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
