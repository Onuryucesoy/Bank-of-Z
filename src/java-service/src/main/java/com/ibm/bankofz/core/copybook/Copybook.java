package com.ibm.bankofz.core.copybook;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A parsed copybook: one or more 01-level records. */
public record Copybook(String name, List<Field> records) {

    public Field primary() {
        return records.get(0);
    }

    public Field record(String recordName) {
        return records.stream().filter(r -> r.name().equals(recordName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " has no record " + recordName));
    }

    /**
     * Flattens a record into addressable elementary items. Keys are the
     * COBOL data name, qualified as {@code NAME OF PARENT} when the name is
     * not unique, with 1-based subscripts for OCCURS, e.g.
     * {@code COMM-ACCNO(3)}. FILLER items are included with key
     * {@code FILLER@offset}.
     */
    public static Map<String, Slot> slots(Field record) {
        List<Slot> raw = new ArrayList<>();
        walk(record, null, 0, "", raw);
        Map<String, Integer> counts = new HashMap<>();
        for (Slot s : raw) {
            counts.merge(s.field().name() + s.subscript(), 1, Integer::sum);
        }
        Map<String, Slot> out = new LinkedHashMap<>();
        for (Slot s : raw) {
            String key;
            if (s.field().isFiller()) {
                key = "FILLER@" + s.offset();
            } else if (counts.get(s.field().name() + s.subscript()) > 1 && s.parent() != null) {
                key = s.field().name() + " OF " + s.parent() + s.subscript();
            } else {
                key = s.field().name() + s.subscript();
            }
            out.put(key, new Slot(key, s.field(), s.parent(), s.offset(), s.subscript()));
        }
        return out;
    }

    private static void walk(Field f, String parent, int shift, String subscript, List<Slot> out) {
        int stride = f.length();
        for (int i = 0; i < f.occursMax(); i++) {
            String sub = f.occursMax() > 1
                    ? (subscript.isEmpty() ? "(" + (i + 1) + ")" : subscript.replace(")", "," + (i + 1) + ")"))
                    : subscript;
            int at = shift + i * stride;
            if (f.children().isEmpty()) {
                out.add(new Slot(null, f, parent, f.offset() + at, sub));
            } else {
                for (Field c : f.children()) {
                    walk(c, f.name(), at, sub, out);
                }
            }
        }
    }

    /** An elementary item at an absolute offset within its record. */
    public record Slot(String key, Field field, String parent, int offset, String subscript) {
    }
}
