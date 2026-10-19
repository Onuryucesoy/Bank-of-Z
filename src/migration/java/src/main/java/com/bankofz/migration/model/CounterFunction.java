package com.bankofz.migration.model;

/**
 * {@code NEWCUSNO-FUNCTION} / {@code NEWACCNO-FUNCTION PIC X} 88-levels from
 * src/base/cics/copy/NEWCUSNO.cpy and NEWACCNO.cpy. The Java replacement of each
 * function is described in docs/migration/phase-1-data-layer.md (section 3) and
 * src/migration/db2/V003__sequences.sql.
 */
public enum CounterFunction {
    /** 88 ...-FUNCTION-GETNEW VALUE 'G': allocate the next number (NEXT VALUE FOR the sequence). */
    GETNEW('G'),
    /** 88 ...-FUNCTION-ROLLBACK VALUE 'R': give a number back; a no-op, the gap is kept. */
    ROLLBACK('R'),
    /** 88 ...-FUNCTION-CURRENT VALUE 'C': read the last allocated number (BANK_COUNTER). */
    CURRENT('C');

    private final char code;

    CounterFunction(char code) {
        this.code = code;
    }

    public char code() {
        return code;
    }

    public static CounterFunction fromCode(char code) {
        for (CounterFunction f : values()) {
            if (f.code == code) {
                return f;
            }
        }
        throw new IllegalArgumentException("Not a counter function 88-level value: '" + code + "'");
    }
}
