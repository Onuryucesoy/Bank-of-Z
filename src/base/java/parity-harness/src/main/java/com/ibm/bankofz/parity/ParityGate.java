package com.ibm.bankofz.parity;

import com.ibm.bankofz.parity.snapshot.Db2Snapshotter;
import com.ibm.bankofz.parity.snapshot.Dbd;
import com.ibm.bankofz.parity.snapshot.ImsSnapshotter;
import com.ibm.bankofz.parity.snapshot.Snapshot;
import com.ibm.bankofz.parity.snapshot.StateDiff;
import com.ibm.bankofz.parity.tape.Tape;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Command line for the migration gate; runs under BPXBATCH/JZOS on z/OS or anywhere with Java 21.
 * Return codes: 0 identical, 4 divergences, 8 COMP-3/numeric divergence (blocker), 12 usage or I/O error.
 */
public final class ParityGate {
    private ParityGate() {
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String... args) {
        try {
            if (args.length == 0) {
                return usage();
            }
            switch (args[0]) {
                case "compare-tapes" -> {
                    need(args, 3);
                    return emit(Replayer.compare(Tape.read(Path.of(args[1])), Tape.read(Path.of(args[2]))));
                }
                case "compare-reports" -> {
                    need(args, 3);
                    return emit(ReportDiff.diff(ReportDiff.lines(args[1]), ReportDiff.lines(args[2])));
                }
                case "diff-snapshots" -> {
                    need(args, 3);
                    Set<String> ignore = args.length > 4 && args[3].equals("--ignore")
                            ? Set.of(args[4].split(",")) : Set.of();
                    return emit(StateDiff.diff(Snapshot.read(Path.of(args[1])), Snapshot.read(Path.of(args[2])), ignore));
                }
                case "snapshot-db2" -> {
                    need(args, 3);
                    List<Db2Snapshotter.TableSpec> specs = new ArrayList<>();
                    for (Db2Snapshotter.TableSpec t : Db2Snapshotter.BANKZ_TABLES) {
                        if (args.length == 3 || List.of(args).subList(3, args.length).contains(t.qualifiedName())) {
                            specs.add(t);
                        }
                    }
                    String user = System.getenv("BANKZ_DB2_USER");
                    try (Connection con = user == null ? DriverManager.getConnection(args[1])
                            : DriverManager.getConnection(args[1], user, System.getenv("BANKZ_DB2_PASSWORD"))) {
                        Db2Snapshotter.snapshot(con, specs).write(Path.of(args[2]));
                    }
                    return 0;
                }
                case "snapshot-ims" -> {
                    need(args, 4);
                    Snapshot s = new Snapshot();
                    try (Stream<Path> dbds = Files.list(Path.of(args[1]))) {
                        for (Path asm : dbds.filter(p -> p.toString().endsWith(".asm")).sorted().toList()) {
                            Dbd dbd = Dbd.parse(asm);
                            for (Dbd.Segment seg : dbd.segments()) {
                                Path unload = Path.of(args[2], dbd.name() + "." + seg.name() + ".unload");
                                if (Files.exists(unload)) {
                                    try (InputStream in = Files.newInputStream(unload)) {
                                        ImsSnapshotter.snapshot(dbd, seg.name(), in, s);
                                    }
                                }
                            }
                        }
                    }
                    s.write(Path.of(args[3]));
                    return 0;
                }
                default -> {
                    return usage();
                }
            }
        } catch (Exception e) {
            System.err.println("ParityGate: " + e);
            return 12;
        }
    }

    private static int emit(ParityReport r) {
        System.out.print(r.render());
        return r.exitCode();
    }

    private static void need(String[] args, int n) {
        if (args.length < n) {
            throw new IllegalArgumentException("missing arguments for " + args[0]);
        }
    }

    private static int usage() {
        System.err.println("""
                usage: ParityGate compare-tapes <legacy.tape> <candidate.tape>
                       ParityGate compare-reports <legacy> <candidate>   (files or //DD:name)
                       ParityGate diff-snapshots <legacy.snap> <candidate.snap> [--ignore TABLE.COL,...]
                       ParityGate snapshot-db2 <jdbc-url> <out.snap> [TABLE ...]
                       ParityGate snapshot-ims <dbd-asm-dir> <unload-dir> <out.snap>""");
        return 12;
    }
}
