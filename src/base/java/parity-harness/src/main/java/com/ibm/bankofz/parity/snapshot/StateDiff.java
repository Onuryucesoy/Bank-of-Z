package com.ibm.bankofz.parity.snapshot;

import com.ibm.bankofz.parity.ParityReport;
import com.ibm.bankofz.parity.compare.Divergence;
import com.ibm.bankofz.parity.compare.Divergence.Kind;
import com.ibm.bankofz.parity.snapshot.Snapshot.Column;
import com.ibm.bankofz.parity.snapshot.Snapshot.Table;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Diffs two snapshots taken after the legacy and the candidate path processed the same inputs from the same
 * starting state. DECIMAL differences are COMP-3 divergences (a phase blocker), including scale-only
 * differences such as {@code 1.5} vs {@code 1.50}, because they reveal a different receiving-field picture.
 */
public final class StateDiff {
    private StateDiff() {
    }

    public static ParityReport diff(Snapshot legacy, Snapshot candidate) {
        return diff(legacy, candidate, java.util.Set.of());
    }

    /**
     * {@code ignore} lists {@code TABLE.COLUMN} values that legitimately differ between runs (timestamps
     * stamped by the clock). DECIMAL columns cannot be ignored: they are always compared.
     */
    public static ParityReport diff(Snapshot legacy, Snapshot candidate, java.util.Set<String> ignore) {
        ParityReport report = new ParityReport("persisted-state parity: legacy vs candidate");
        TreeSet<String> names = new TreeSet<>(legacy.tables().keySet());
        names.addAll(candidate.tables().keySet());
        for (String name : names) {
            Table a = legacy.tables().get(name);
            Table b = candidate.tables().get(name);
            if (a == null || b == null) {
                report.add(new Divergence(Kind.MISSING, name, "<table>", a == null ? "absent" : "present",
                        b == null ? "absent" : "present"));
                continue;
            }
            if (!a.columns().equals(b.columns())) {
                report.add(new Divergence(Kind.MISSING, name, "<columns>", a.columns().toString(),
                        b.columns().toString()));
                continue;
            }
            diffTable(report, a, b, ignore);
        }
        return report;
    }

    private static void diffTable(ParityReport report, Table a, Table b, java.util.Set<String> ignore) {
        TreeSet<String> keys = new TreeSet<>(a.rows().keySet());
        keys.addAll(b.rows().keySet());
        List<Column> cols = a.columns();
        for (String key : keys) {
            report.countCompared();
            List<String> ra = a.rows().get(key);
            List<String> rb = b.rows().get(key);
            String scope = a.name() + "[" + key + "]";
            if (ra == null) {
                report.add(new Divergence(Kind.ROW_ADDED, scope, "<row>", "absent", String.join("|", nn(rb))));
                continue;
            }
            if (rb == null) {
                report.add(new Divergence(Kind.ROW_REMOVED, scope, "<row>", String.join("|", nn(ra)), "absent"));
                continue;
            }
            for (int i = 0; i < cols.size(); i++) {
                String va = ra.get(i);
                String vb = rb.get(i);
                if (Objects.equals(va, vb)) {
                    continue;
                }
                Column c = cols.get(i);
                if (c.kind() != Snapshot.ColumnKind.DECIMAL && ignore.contains(a.name() + "." + c.name())) {
                    continue;
                }
                report.add(new Divergence(kind(c, va, vb), scope, c.name(), String.valueOf(va), String.valueOf(vb)));
            }
        }
    }

    private static Kind kind(Column c, String a, String b) {
        boolean badA = a != null && a.startsWith("!HEX:");
        boolean badB = b != null && b.startsWith("!HEX:");
        return switch (c.kind()) {
            case DECIMAL -> Kind.COMP3;
            case INTEGER -> badA != badB ? Kind.INVALID_DATA : Kind.NUMERIC;
            case BINARY -> Kind.BYTES;
            case TEXT -> Kind.TEXT;
        };
    }

    private static List<String> nn(List<String> r) {
        return r.stream().map(v -> v == null ? Snapshot.NULL : v).toList();
    }
}
