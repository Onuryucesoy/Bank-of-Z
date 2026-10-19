package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Byte layout of one 01-level copybook record. */
public final class RecordLayout {
    private final String name;
    private final String source;
    private final int length;
    private final Charset charset;
    private final List<FieldSpec> fields;
    private final Map<String, FieldSpec> byPath = new LinkedHashMap<>();

    public RecordLayout(String name, String source, int length, Charset charset, List<FieldSpec> fields) {
        this.name = name;
        this.source = source;
        this.length = length;
        this.charset = charset;
        this.fields = List.copyOf(fields);
        for (FieldSpec f : this.fields) {
            byPath.put(f.path(), f);
        }
    }

    public String name() { return name; }
    /** Repository-relative copybook path this layout was generated from. */
    public String source() { return source; }
    /** Maximum record length (OCCURS DEPENDING ON tables at their maximum). */
    public int length() { return length; }
    public Charset charset() { return charset; }
    public List<FieldSpec> fields() { return fields; }

    public FieldSpec field(String path) {
        FieldSpec f = byPath.get(path);
        if (f == null) {
            throw new IllegalArgumentException(name + " has no field " + path);
        }
        return f;
    }

    /** Elementary fields that are not alternate REDEFINES views: together they cover the canonical bytes. */
    public List<FieldSpec> canonicalLeaves() {
        List<FieldSpec> out = new ArrayList<>();
        for (FieldSpec f : fields) {
            if (f.kind() != FieldSpec.Kind.GROUP && !f.underRedefines()) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Renders every canonical leaf occurrence as {@code path[i][j] -> value}. Numeric fields that would
     * raise a data exception are rendered as {@code !HEX:...} so diffs still show them.
     */
    public Map<String, String> render(byte[] buf, int offset) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FieldSpec f : canonicalLeaves()) {
            int[] idx = new int[f.dimensions()];
            renderOccurrences(f, buf, offset, idx, 0, out);
        }
        return out;
    }

    private void renderOccurrences(FieldSpec f, byte[] buf, int base, int[] idx, int dim, Map<String, String> out) {
        if (dim == idx.length) {
            StringBuilder key = new StringBuilder(f.path());
            for (int i : idx) {
                key.append('[').append(i).append(']');
            }
            int at = base + f.offsetOf(idx);
            if (at + f.length() > buf.length) {
                return;
            }
            out.put(key.toString(), renderValue(f, buf, at));
            return;
        }
        for (int i = 0; i < f.maxOccurs(dim); i++) {
            idx[dim] = i;
            renderOccurrences(f, buf, base, idx, dim + 1, out);
        }
    }

    String renderValue(FieldSpec f, byte[] buf, int at) {
        try {
            return switch (f.kind()) {
                case ALPHANUMERIC, EDITED -> Text.decode(buf, at, f.length(), charset);
                case ZONED -> ZonedDecimal.decode(buf, at, f.digits(), f.scale(), f.sign()).toPlainString();
                case PACKED -> PackedDecimal.decode(buf, at, f.digits(), f.scale()).toPlainString();
                case BINARY, BINARY_NATIVE -> Binary.decode(buf, at, f.digits(), f.scale(), f.signed()).toPlainString();
                case POINTER, GROUP -> "X'" + Hex.of(buf, at, f.length()) + "'";
            };
        } catch (CobolDataException e) {
            return "!HEX:" + Hex.of(buf, at, f.length());
        }
    }

    /** Decodes a numeric field occurrence; used by the harness to compare values rather than bytes. */
    public BigDecimal numeric(FieldSpec f, byte[] buf, int at) {
        return switch (f.kind()) {
            case ZONED -> ZonedDecimal.decode(buf, at, f.digits(), f.scale(), f.sign());
            case PACKED -> PackedDecimal.decode(buf, at, f.digits(), f.scale());
            case BINARY, BINARY_NATIVE -> Binary.decode(buf, at, f.digits(), f.scale(), f.signed());
            default -> throw new IllegalArgumentException(f.path() + " is not numeric");
        };
    }
}
