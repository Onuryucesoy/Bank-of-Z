package com.ibm.bankofz.core.golden;

import com.ibm.bankofz.core.copybook.Copybook;
import com.ibm.bankofz.core.copybook.RecordCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares an actual record image (from the Java port, or captured from
 * CICS) with a golden-master expectation, field by field and byte by byte.
 * Fields absent from the expectation must hold their INITIALIZE default;
 * fields listed as non-deterministic are only checked for presence.
 */
public final class GoldenMasterComparator {

    /** One differing elementary item. */
    public record Mismatch(String field, int offset, String expected, String actual) {
        @Override
        public String toString() {
            return field + "@" + offset + ": expected [" + expected + "] but was [" + actual + "]";
        }
    }

    private final RecordCodec codec;

    public GoldenMasterComparator(RecordCodec codec) {
        this.codec = codec;
    }

    public List<Mismatch> compare(Map<String, String> expected, byte[] actual, Collection<String> nonDeterministic) {
        Set<String> skip = Set.copyOf(nonDeterministic);
        Map<String, String> defaults = codec.decode(codec.encode(Map.of()));
        Map<String, String> got = codec.decode(actual);
        List<Mismatch> out = new ArrayList<>();
        for (Map.Entry<String, Copybook.Slot> e : codec.slots().entrySet()) {
            String key = e.getKey();
            Copybook.Slot slot = e.getValue();
            if (slot.field().isFiller() || slot.field().underRedefines() || skip.contains(key) || !got.containsKey(key)) {
                continue;
            }
            String want = expected.containsKey(key) ? expected.get(key) : defaults.get(key);
            if (!same(slot, want, got.get(key))) {
                out.add(new Mismatch(key, slot.offset(), want, got.get(key)));
            }
        }
        return out;
    }

    /** Exact byte comparison; reports the first differing offset and the item covering it. */
    public List<Mismatch> compareBytes(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return List.of(new Mismatch("<length>", 0, String.valueOf(expected.length), String.valueOf(actual.length)));
        }
        int at = RecordCodec.firstDifference(expected, actual);
        if (at < 0) {
            return List.of();
        }
        return List.of(new Mismatch(codec.itemAt(at), at, String.format("%02X", expected[at] & 0xFF),
                String.format("%02X", actual[at] & 0xFF)));
    }

    private static boolean same(Copybook.Slot slot, String want, String got) {
        return RecordCodec.sameValue(slot, want, got);
    }
}
