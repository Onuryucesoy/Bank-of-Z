package com.ibm.bankofz.ims.jmp;

/**
 * A qualified SSA {@code SEGMENT(FIELD   = value)}: {@code value} is the exact key bytes the COBOL SSA
 * carries and {@code fieldOffset} where that field sits in the segment (DBD START - 1).
 */
public record Ssa(String segment, String field, int fieldOffset, byte[] value) {
    public Ssa {
        value = value.clone();
    }

    @Override
    public byte[] value() {
        return value.clone();
    }
}
