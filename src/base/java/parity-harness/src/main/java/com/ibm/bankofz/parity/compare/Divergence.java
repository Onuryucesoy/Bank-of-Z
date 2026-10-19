package com.ibm.bankofz.parity.compare;

/** One difference between the legacy and the candidate side, located by field path or row key. */
public record Divergence(Kind kind, String scope, String path, String legacy, String candidate) {

    public enum Kind {
        /** A COMP-3 field (or a Db2/IMS DECIMAL column, which is packed on disk) differs: always a blocker. */
        COMP3,
        /** A zoned or binary numeric field differs. */
        NUMERIC,
        /** One side holds bytes that would raise a data exception (S0C7) and the other does not. */
        INVALID_DATA,
        TEXT,
        /** Raw bytes differ in an area that has no copybook. */
        BYTES,
        LENGTH,
        OUTCOME,
        MISSING,
        ROW_ADDED,
        ROW_REMOVED
    }

    /**
     * COMP-3 and other numeric value divergences block the phase: COMMAREA amounts are often zoned
     * (PAYDBCR COMM-AMT is S9(10)V99 DISPLAY) but end up in packed Db2/IMS DECIMAL columns.
     */
    public boolean blocker() {
        return kind == Kind.COMP3 || kind == Kind.NUMERIC;
    }

    @Override
    public String toString() {
        return kind + "\t" + scope + "\t" + path + "\tlegacy=" + legacy + "\tcandidate=" + candidate;
    }
}
