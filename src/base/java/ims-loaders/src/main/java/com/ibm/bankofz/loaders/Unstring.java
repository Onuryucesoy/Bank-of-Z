package com.ibm.bankofz.loaders;

/**
 * COBOL {@code UNSTRING src DELIMITED BY d1 OR d2 ... INTO r1 r2 ...} for alphanumeric receivers.
 * At each position the delimiters are tried in the order written; contiguous delimiters give a
 * blank receiver; receivers not reached before the data runs out keep their previous value.
 */
final class Unstring {
    static final String[] LOADER_DELIMITERS = {"\",\"", "\",", ",\"", ",", "\""};

    private Unstring() {
    }

    /** {@code receivers} hold the current values (lengths = PIC X sizes); updated in place. */
    static void into(String src, String[] delimiters, String[] receivers, int[] sizes) {
        int pos = 0;
        int field = 0;
        int start = 0;
        while (field < receivers.length && pos <= src.length()) {
            if (pos == src.length()) {
                if (start < src.length()) {
                    receivers[field] = move(src.substring(start), sizes[field]);
                }
                return;
            }
            String hit = null;
            for (String d : delimiters) {
                if (src.startsWith(d, pos)) {
                    hit = d;
                    break;
                }
            }
            if (hit == null) {
                pos++;
                continue;
            }
            receivers[field++] = move(src.substring(start, pos), sizes[field - 1]);
            pos += hit.length();
            start = pos;
        }
    }

    /** MOVE alphanumeric to PIC X(n): truncate on the right or pad with spaces. */
    static String move(String s, int n) {
        return s.length() >= n ? s.substring(0, n) : s + " ".repeat(n - s.length());
    }
}
