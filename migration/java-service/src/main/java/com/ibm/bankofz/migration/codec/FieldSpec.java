package com.ibm.bankofz.migration.codec;

import java.util.Objects;

/**
 * One elementary COBOL item inside a fixed-length record, as listed in {@code migration/copybook-field-mapping.json}
 * (0-based {@code offset}, {@code size} in bytes, {@code digits}/{@code scale} from the PIC clause).
 */
public record FieldSpec(String name, int offset, int size, Kind kind, int digits, int scale, boolean signed) {

    public enum Kind {
        /** PIC X(n) / A(n): fixed-length EBCDIC text. */
        ALPHANUMERIC,
        /** PIC 9 / S9 USAGE DISPLAY: zoned decimal. */
        ZONED,
        /** PIC 9(8)-style dates/times: zoned digits kept as a raw zero-padded String (no java.time). */
        RAW_DIGITS,
        /** COMP-3 / PACKED-DECIMAL. */
        PACKED,
        /** COMP / COMP-4 / BINARY / COMP-5: big-endian two's complement. */
        BINARY
    }

    public FieldSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        if (offset < 0 || size <= 0) {
            throw new IllegalArgumentException(name + ": offset must be >= 0 and size > 0");
        }
    }

    public static FieldSpec alphanumeric(String name, int offset, int length) {
        return new FieldSpec(name, offset, length, Kind.ALPHANUMERIC, 0, 0, false);
    }

    public static FieldSpec zoned(String name, int offset, int digits, int scale, boolean signed) {
        return new FieldSpec(name, offset, digits, Kind.ZONED, digits, scale, signed);
    }

    public static FieldSpec rawDigits(String name, int offset, int digits) {
        return new FieldSpec(name, offset, digits, Kind.RAW_DIGITS, digits, 0, false);
    }

    public static FieldSpec packed(String name, int offset, int digits, int scale, boolean signed) {
        return new FieldSpec(name, offset, CopybookCodec.packedSize(digits), Kind.PACKED, digits, scale, signed);
    }

    public static FieldSpec binary(String name, int offset, int digits, int scale, boolean signed) {
        return new FieldSpec(name, offset, CopybookCodec.binarySize(digits), Kind.BINARY, digits, scale, signed);
    }

    public int end() {
        return offset + size;
    }
}
