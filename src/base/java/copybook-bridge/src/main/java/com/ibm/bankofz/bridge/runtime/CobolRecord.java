package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Arrays;

/**
 * A byte-exact view over a copybook record. The view never copies: setters write straight into the
 * backing array, so the same bytes can be handed to EXEC CICS LINK, an IMS message queue or a DL/I call.
 * OCCURS subscripts are 0-based (COBOL subscript minus one).
 */
public abstract class CobolRecord {
    private final RecordLayout layout;
    private final byte[] buf;
    private final int base;

    protected CobolRecord(RecordLayout layout, byte[] buf, int base) {
        this(layout, buf, base, layout.length());
    }

    /** {@code minimumLength}: bytes that must exist, i.e. up to the start of a trailing ODO table. */
    protected CobolRecord(RecordLayout layout, byte[] buf, int base, int minimumLength) {
        if (base < 0 || base + minimumLength > buf.length) {
            throw new IllegalArgumentException(layout.name() + " needs " + layout.length()
                    + " bytes at offset " + base + ", buffer has " + buf.length);
        }
        this.layout = layout;
        this.buf = buf;
        this.base = base;
    }

    public final RecordLayout layout() {
        return layout;
    }

    /** The backing array (shared, not copied). */
    public final byte[] buffer() {
        return buf;
    }

    public final int baseOffset() {
        return base;
    }

    /** Copy of the record's bytes (maximum length). */
    public final byte[] toBytes() {
        return Arrays.copyOfRange(buf, base, Math.min(buf.length, base + layout.length()));
    }

    /** Length the COBOL program would pass (differs from {@code layout().length()} only for ODO records). */
    public int actualLength() {
        return layout.length();
    }

    /** COBOL {@code INITIALIZE}: alphanumeric/edited items to spaces, numeric items to zero, FILLER untouched. */
    public final void initialize() {
        Charset cs = layout.charset();
        for (FieldSpec f : layout.canonicalLeaves()) {
            if (f.name() == null || f.name().equals("FILLER")) {
                continue;
            }
            forEachOccurrence(f, at -> {
                switch (f.kind()) {
                    case ALPHANUMERIC -> Text.fill(buf, at, f.length(), Ebcdic.space(cs));
                    case EDITED -> Text.encode(Edited.format(BigDecimal.ZERO, f.picture()), buf, at, f.length(), cs);
                    case POINTER -> Text.fill(buf, at, f.length(), (byte) 0);
                    default -> putNumeric(f, at, BigDecimal.ZERO, false);
                }
            });
        }
    }

    protected final String getString(FieldSpec f, int... idx) {
        return Text.decode(buf, base + f.offsetOf(idx), f.length(), layout.charset());
    }

    protected final void setString(FieldSpec f, String value, int... idx) {
        Text.encode(value, buf, base + f.offsetOf(idx), f.length(), layout.charset());
    }

    protected final byte[] getBytes(FieldSpec f, int... idx) {
        int at = base + f.offsetOf(idx);
        return Arrays.copyOfRange(buf, at, at + f.length());
    }

    /** Group MOVE semantics: copy left-aligned, pad with spaces, truncate on the right. */
    protected final void setBytes(FieldSpec f, byte[] value, int... idx) {
        int at = base + f.offsetOf(idx);
        int n = Math.min(value.length, f.length());
        System.arraycopy(value, 0, buf, at, n);
        Arrays.fill(buf, at + n, at + f.length(), Ebcdic.space(layout.charset()));
    }

    protected final BigDecimal getDecimal(FieldSpec f, int... idx) {
        int at = base + f.offsetOf(idx);
        if (f.kind() == FieldSpec.Kind.EDITED) {
            return Edited.parse(Text.decode(buf, at, f.length(), layout.charset()));
        }
        return layout.numeric(f, buf, at);
    }

    protected final long getLong(FieldSpec f, int... idx) {
        return getDecimal(f, idx).longValueExact();
    }

    protected final void setDecimal(FieldSpec f, BigDecimal value, boolean rounded, int... idx) {
        int at = base + f.offsetOf(idx);
        if (f.kind() == FieldSpec.Kind.EDITED) {
            BigDecimal v = rounded ? Assign.fit(value, f.digits(), f.scale(), true, true) : value;
            Text.encode(Edited.format(v, f.picture()), buf, at, f.length(), layout.charset());
            return;
        }
        putNumeric(f, at, value, rounded);
    }

    protected final void setLong(FieldSpec f, long value, int... idx) {
        setDecimal(f, BigDecimal.valueOf(value), false, idx);
    }

    /** COBOL class test {@code IF field NUMERIC}. */
    protected final boolean isNumericValid(FieldSpec f, int... idx) {
        int at = base + f.offsetOf(idx);
        return switch (f.kind()) {
            case ZONED -> ZonedDecimal.isValid(buf, at, f.digits(), f.sign());
            case PACKED -> PackedDecimal.isValid(buf, at, f.digits());
            default -> true;
        };
    }

    /** Level-88 test against literal byte images (already padded to the field length). */
    protected final boolean matchesAny(FieldSpec f, byte[][] values, int... idx) {
        int at = base + f.offsetOf(idx);
        for (byte[] v : values) {
            if (Arrays.equals(buf, at, at + f.length(), v, 0, v.length)) {
                return true;
            }
        }
        return false;
    }

    /** Level-88 test against numeric values / THRU ranges ({@code ranges[i] = {low, high}}). */
    protected final boolean inRanges(FieldSpec f, BigDecimal[][] ranges, int... idx) {
        if (!isNumericValid(f, idx)) {
            return false;
        }
        BigDecimal v = getDecimal(f, idx);
        for (BigDecimal[] r : ranges) {
            if (v.compareTo(r[0]) >= 0 && v.compareTo(r[1]) <= 0) {
                return true;
            }
        }
        return false;
    }

    protected final void setRaw(FieldSpec f, byte[] image, int... idx) {
        System.arraycopy(image, 0, buf, base + f.offsetOf(idx), f.length());
    }

    private void putNumeric(FieldSpec f, int at, BigDecimal value, boolean rounded) {
        BigDecimal v = rounded ? Assign.fit(value, f.digits(), f.scale(), f.signed(), true) : value;
        switch (f.kind()) {
            case ZONED -> ZonedDecimal.encode(v, buf, at, f.digits(), f.scale(), f.sign());
            case PACKED -> PackedDecimal.encode(v, buf, at, f.digits(), f.scale(), f.signed());
            case BINARY -> Binary.encode(v, buf, at, f.digits(), f.scale(), f.signed(), false);
            case BINARY_NATIVE -> Binary.encode(v, buf, at, f.digits(), f.scale(), f.signed(), true);
            default -> throw new IllegalArgumentException(f.path() + " is not numeric");
        }
    }

    private void forEachOccurrence(FieldSpec f, java.util.function.IntConsumer action) {
        int[] idx = new int[f.dimensions()];
        walk(f, idx, 0, action);
    }

    private void walk(FieldSpec f, int[] idx, int dim, java.util.function.IntConsumer action) {
        if (dim == idx.length) {
            int at = base + f.offsetOf(idx);
            if (at + f.length() <= buf.length) {
                action.accept(at);
            }
            return;
        }
        for (int i = 0; i < f.maxOccurs(dim); i++) {
            idx[dim] = i;
            walk(f, idx, dim + 1, action);
        }
    }
}
