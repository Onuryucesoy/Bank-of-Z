package com.ibm.bankofz.bridge.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * One data item of a copybook with its absolute offset inside the 01-level record.
 * {@code counts}/{@code strides} describe enclosing OCCURS dimensions, outermost first.
 */
public final class FieldSpec {
    public enum Kind { GROUP, ALPHANUMERIC, ZONED, PACKED, BINARY, BINARY_NATIVE, EDITED, POINTER }

    private final int level;
    private final String name;
    private final String path;
    private final int offset;
    private final int length;
    private final Kind kind;
    private final int digits;
    private final int scale;
    private final boolean signed;
    private final ZonedDecimal.Sign sign;
    private final String picture;
    private final int[] counts;
    private final int[] strides;
    private final boolean underRedefines;
    private final String dependingOn;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public FieldSpec(int level, String name, String path, int offset, int length, Kind kind, int digits, int scale,
                     boolean signed, ZonedDecimal.Sign sign, String picture, int[] counts, int[] strides,
                     boolean underRedefines, String dependingOn) {
        this.level = level;
        this.name = name;
        this.path = path;
        this.offset = offset;
        this.length = length;
        this.kind = kind;
        this.digits = digits;
        this.scale = scale;
        this.signed = signed;
        this.sign = sign;
        this.picture = picture;
        this.counts = counts.clone();
        this.strides = strides.clone();
        this.underRedefines = underRedefines;
        this.dependingOn = dependingOn;
        if (counts.length != strides.length) {
            throw new IllegalArgumentException("counts/strides mismatch for " + path);
        }
    }

    public int level() { return level; }
    public String name() { return name; }
    /** Qualified COBOL path, e.g. {@code OUTPUT-AREA/ACCOUNT-SUMMARY/BALANCE-AS}. */
    public String path() { return path; }
    /** Offset of the first occurrence relative to the start of the record. */
    public int offset() { return offset; }
    public int length() { return length; }
    public Kind kind() { return kind; }
    public int digits() { return digits; }
    public int scale() { return scale; }
    public boolean signed() { return signed; }
    public ZonedDecimal.Sign sign() { return sign; }
    public String picture() { return picture; }
    public int dimensions() { return counts.length; }
    public int maxOccurs(int dim) { return counts[dim]; }
    public int stride(int dim) { return strides[dim]; }
    /** True for items that are, or sit under, a REDEFINES (alternate views of earlier bytes). */
    public boolean underRedefines() { return underRedefines; }
    /** COBOL name of the ODO object when this item is, or sits under, OCCURS ... DEPENDING ON. */
    public String dependingOn() { return dependingOn; }
    public boolean isNumeric() {
        return kind == Kind.ZONED || kind == Kind.PACKED || kind == Kind.BINARY || kind == Kind.BINARY_NATIVE;
    }

    /** Offset of the occurrence selected by 0-based {@code index} values, relative to the record start. */
    public int offsetOf(int... index) {
        if (index.length != counts.length) {
            throw new IllegalArgumentException(path + " needs " + counts.length + " subscript(s), got " + index.length);
        }
        int off = offset;
        for (int d = 0; d < index.length; d++) {
            Objects.checkIndex(index[d], counts[d]);
            off += index[d] * strides[d];
        }
        return off;
    }

    @Override
    public String toString() {
        return path + "@" + offset + "+" + length + " " + kind
                + (picture == null ? "" : " PIC " + picture)
                + (counts.length == 0 ? "" : " OCCURS " + Arrays.toString(counts));
    }
}
