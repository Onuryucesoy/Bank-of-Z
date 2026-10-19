package com.ibm.bankofz.parity;

import com.ibm.bankofz.parity.compare.Divergence;
import com.ibm.bankofz.parity.compare.Divergence.Kind;
import com.ibm.bankofz.parity.compare.RecordComparator;

import com.ibm.bankofz.parity.tape.Exchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays captured requests against a candidate implementation and compares responses, or compares two
 * tapes recorded side by side (legacy and Java programs fed the same inputs in a parallel run).
 */
public final class Replayer {

    /** A program under test: receives the recorded request bytes, returns its response bytes and outcome. */
    @FunctionalInterface
    public interface Target {
        Result invoke(Exchange recorded) throws Exception;
    }

    public record Result(byte[] response, String outcome) {
        public static Result ok(byte[] response) {
            return new Result(response, "OK");
        }
    }

    private Replayer() {
    }

    /** Replays every exchange, returning the candidate tape (same seq numbers) for archiving. */
    public static List<Exchange> replay(List<Exchange> legacy, Target target) {
        List<Exchange> candidate = new ArrayList<>();
        for (Exchange e : legacy) {
            Result r;
            try {
                r = target.invoke(e);
            } catch (Exception ex) {
                r = new Result(new byte[0], "EXCEPTION:" + ex.getClass().getSimpleName());
            }
            candidate.add(e.withResult(r.response(), r.outcome()));
        }
        return candidate;
    }

    public static ParityReport compare(List<Exchange> legacy, List<Exchange> candidate) {
        ParityReport report = new ParityReport("tape parity: legacy vs candidate");
        Map<String, Exchange> other = new LinkedHashMap<>();
        for (Exchange e : candidate) {
            other.put(key(e), e);
        }
        for (Exchange l : legacy) {
            report.countCompared();
            Exchange c = other.remove(key(l));
            String scope = l.system() + ":" + l.program() + "#" + l.seq();
            if (c == null) {
                report.add(new Divergence(Kind.MISSING, scope, "<exchange>", "present", "absent"));
                continue;
            }
            if (!java.util.Arrays.equals(l.request(), c.request())) {
                report.add(new Divergence(Kind.BYTES, scope, "<request>", "recorded", "different input"));
            }
            if (!l.outcome().equals(c.outcome())) {
                report.add(new Divergence(Kind.OUTCOME, scope, "<outcome>", l.outcome(), c.outcome()));
            }
            java.util.Set<String> vol = Catalog.find(l.system(), l.program())
                    .map(Catalog.Entry::volatileFields).orElse(java.util.Set.of());
            for (Divergence d : RecordComparator.compare(scope, Layouts.resolve(l.responseLayout()),
                    l.response(), c.response())) {
                String path = d.path().replaceAll("\\[\\d+]", "");
                if (d.kind() == Kind.COMP3 || !vol.contains(path)) {
                    report.add(d);
                }
            }
        }
        for (Exchange c : other.values()) {
            report.add(new Divergence(Kind.MISSING, c.system() + ":" + c.program() + "#" + c.seq(),
                    "<exchange>", "absent", "present"));
        }
        return report;
    }

    private static String key(Exchange e) {
        return e.system() + ":" + e.program() + "#" + e.seq();
    }
}
