package com.ibm.bankofz.parity;

import com.ibm.bankofz.parity.compare.Divergence;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of a parity check. Exit codes follow JCL step conventions so the gate can be a COND/IF step:
 * 0 identical, 4 divergences, 8 COMP-3 or numeric divergence (phase blocker).
 */
public final class ParityReport {
    public static final int RC_CLEAN = 0;
    public static final int RC_DIVERGED = 4;
    public static final int RC_COMP3_BLOCKER = 8;

    private final String title;
    private final List<Divergence> divergences = new ArrayList<>();
    private int compared;

    public ParityReport(String title) {
        this.title = title;
    }

    public void countCompared() {
        compared++;
    }

    public void add(Divergence d) {
        divergences.add(d);
    }

    public void addAll(List<Divergence> ds) {
        divergences.addAll(ds);
    }

    public List<Divergence> divergences() {
        return List.copyOf(divergences);
    }

    public int compared() {
        return compared;
    }

    public boolean blocked() {
        return divergences.stream().anyMatch(Divergence::blocker);
    }

    public int exitCode() {
        return blocked() ? RC_COMP3_BLOCKER : divergences.isEmpty() ? RC_CLEAN : RC_DIVERGED;
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append('\n');
        sb.append("compared=").append(compared).append(" divergences=").append(divergences.size())
                .append(" blockers=").append(divergences.stream().filter(Divergence::blocker).count())
                .append(" rc=").append(exitCode()).append('\n');
        if (blocked()) {
            sb.append("BLOCKER: COMP-3/numeric divergence. Do not proceed to the next migration phase.\n");
        }
        for (Divergence d : divergences) {
            sb.append(d).append('\n');
        }
        return sb.toString();
    }
}
