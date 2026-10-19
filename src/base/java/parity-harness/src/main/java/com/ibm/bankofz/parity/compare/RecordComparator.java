package com.ibm.bankofz.parity.compare;

import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Hex;
import com.ibm.bankofz.bridge.runtime.RecordLayout;
import com.ibm.bankofz.parity.compare.Divergence.Kind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares two copies of a record. Byte equality is the pass criterion; when bytes differ, the generated
 * layout attributes each difference to a field so the report says which COMP-3 field diverged and how.
 */
public final class RecordComparator {
    private RecordComparator() {
    }

    public static List<Divergence> compare(String scope, RecordLayout layout, byte[] legacy, byte[] candidate) {
        List<Divergence> out = new ArrayList<>();
        if (Arrays.equals(legacy, candidate)) {
            return out;
        }
        if (legacy.length != candidate.length) {
            out.add(new Divergence(Kind.LENGTH, scope, "<length>", Integer.toString(legacy.length),
                    Integer.toString(candidate.length)));
        }
        if (layout == null) {
            int n = Math.min(legacy.length, candidate.length);
            for (int i = 0; i < n; i++) {
                if (legacy[i] != candidate[i]) {
                    int end = Math.min(n, i + 16);
                    out.add(new Divergence(Kind.BYTES, scope, "@" + i, Hex.of(legacy, i, end - i),
                            Hex.of(candidate, i, end - i)));
                    break;
                }
            }
            return out;
        }
        Map<String, FieldSpec> byPath = new HashMap<>();
        for (FieldSpec f : layout.canonicalLeaves()) {
            byPath.put(f.path(), f);
        }
        Map<String, String> a = layout.render(legacy, 0);
        Map<String, String> b = layout.render(candidate, 0);
        for (Map.Entry<String, String> e : a.entrySet()) {
            String other = b.get(e.getKey());
            if (other == null || e.getValue().equals(other)) {
                continue;
            }
            String key = e.getKey();
            int bracket = key.indexOf('[');
            FieldSpec f = byPath.get(bracket < 0 ? key : key.substring(0, bracket));
            out.add(new Divergence(classify(f, e.getValue(), other), scope, key, e.getValue(), other));
        }
        if (out.isEmpty() || (out.size() == 1 && out.get(0).kind() == Kind.LENGTH)) {
            // Same rendered values but different bytes: e.g. sign nibble C vs F, or bytes past the record.
            int n = Math.min(legacy.length, candidate.length);
            for (int i = 0; i < n; i++) {
                if (legacy[i] != candidate[i]) {
                    FieldSpec f = fieldAt(layout, i);
                    Kind k = f != null && f.kind() == FieldSpec.Kind.PACKED ? Kind.COMP3 : Kind.BYTES;
                    out.add(new Divergence(k, scope, (f == null ? "" : f.path()) + "@" + i,
                            Hex.of(legacy, i, 1), Hex.of(candidate, i, 1)));
                    break;
                }
            }
        }
        return out;
    }

    private static Kind classify(FieldSpec f, String legacy, String candidate) {
        if (f != null && f.kind() == FieldSpec.Kind.PACKED) {
            return Kind.COMP3;
        }
        if (legacy.startsWith("!HEX:") != candidate.startsWith("!HEX:")) {
            return Kind.INVALID_DATA;
        }
        if (f != null && f.isNumeric()) {
            return Kind.NUMERIC;
        }
        return Kind.TEXT;
    }

    private static FieldSpec fieldAt(RecordLayout layout, int offset) {
        for (FieldSpec f : layout.canonicalLeaves()) {
            int stride = f.dimensions() == 0 ? 0 : f.stride(0);
            int count = f.dimensions() == 0 ? 1 : f.maxOccurs(0);
            for (int i = 0; i < count; i++) {
                int start = f.offset() + i * stride;
                if (offset >= start && offset < start + f.length()) {
                    return f;
                }
            }
        }
        return null;
    }
}
