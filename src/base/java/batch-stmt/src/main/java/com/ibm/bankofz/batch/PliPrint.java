package com.ibm.bankofz.batch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

/**
 * Models the PL/I {@code SYSPRINT} stream file BNKSTMT writes to: records carry an ASA control character
 * ('1' new page, ' ' next line), lines longer than LINESIZE continue on the next record, and PAGESIZE
 * overflow starts a new page (the default ENDPAGE action). Defaults are the PL/I PRINT-file defaults.
 */
final class PliPrint {
    static final int DEFAULT_LINESIZE = 120;
    static final int DEFAULT_PAGESIZE = 60;
    static final int TAB = 24;

    private final Writer out;
    private final int lineSize;
    private final int pageSize;
    private StringBuilder cur;
    private char control;
    private int lineOnPage;
    private boolean newPage = true;

    PliPrint(Writer out, int lineSize, int pageSize) {
        this.out = out;
        this.lineSize = lineSize;
        this.pageSize = pageSize;
    }

    /** {@code PUT SKIP EDIT(s) (A)}. */
    void skipEdit(String s) {
        skip();
        put(s);
    }

    /** {@code PUT SKIP LIST(a, b, ...)}: items start on successive tab positions, separated by at least one blank. */
    void skipList(Object... items) {
        skip();
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                int col = cur.length() + 1;
                int target = ((col + TAB - 1) / TAB) * TAB;
                put(" ".repeat(target - cur.length()));
            }
            put(String.valueOf(items[i]));
        }
    }

    /** {@code PUT PAGE}. */
    void page() {
        endLine();
        newPage = true;
    }

    void close() {
        endLine();
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void skip() {
        endLine();
        startLine();
    }

    private void put(String s) {
        if (cur == null) {
            startLine();
        }
        for (int i = 0; i < s.length(); i++) {
            if (cur.length() == lineSize) {
                endLine();
                startLine();
            }
            cur.append(s.charAt(i));
        }
    }

    private void startLine() {
        lineOnPage++;
        control = ' ';
        if (newPage || lineOnPage > pageSize) {
            control = '1';
            lineOnPage = 1;
            newPage = false;
        }
        cur = new StringBuilder();
    }

    private void endLine() {
        if (cur == null) {
            return;
        }
        try {
            out.write(control);
            out.write(cur.toString());
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        cur = null;
    }
}
