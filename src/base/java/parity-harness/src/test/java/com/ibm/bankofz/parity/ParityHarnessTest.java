package com.ibm.bankofz.parity;

import com.ibm.bankofz.bridge.copybook.cics.Accdb2;
import com.ibm.bankofz.bridge.copybook.cics.CicsCopybooks;
import com.ibm.bankofz.bridge.copybook.cics.Customer;
import com.ibm.bankofz.bridge.runtime.Db2Table;
import com.ibm.bankofz.bridge.runtime.Ebcdic;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.RecordLayout;
import com.ibm.bankofz.bridge.runtime.Text;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;
import com.ibm.bankofz.parity.compare.Divergence;
import com.ibm.bankofz.parity.snapshot.Db2Snapshotter;
import com.ibm.bankofz.parity.snapshot.Dbd;
import com.ibm.bankofz.parity.snapshot.ImsSnapshotter;
import com.ibm.bankofz.parity.snapshot.Snapshot;
import com.ibm.bankofz.parity.snapshot.StateDiff;
import com.ibm.bankofz.parity.tape.Exchange;
import com.ibm.bankofz.parity.tape.Tape;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParityHarnessTest {
    static final Path REPO = Path.of(System.getProperty("bankz.repoRoot"));

    /** No CICS COMMAREA has a scaled COMP-3 field (amounts are zoned), so the replay tests use an IMS message. */
    static final String LAYOUT_KEY = com.ibm.bankofz.bridge.copybook.ims.ImsCopybooks.LAYOUTS.entrySet().stream()
            .filter(e -> e.getValue().canonicalLeaves().stream().anyMatch(f -> f.kind() == FieldSpec.Kind.PACKED && f.scale() >= 2 && f.dimensions() == 0)
                    && e.getValue().canonicalLeaves().stream().anyMatch(f -> f.kind() == FieldSpec.Kind.ALPHANUMERIC && f.dimensions() == 0))
            .map(java.util.Map.Entry::getKey).sorted().findFirst().orElseThrow();
    static final RecordLayout LAYOUT = com.ibm.bankofz.bridge.copybook.ims.ImsCopybooks.LAYOUTS.get(LAYOUT_KEY);

    static FieldSpec first(FieldSpec.Kind kind) {
        return LAYOUT.canonicalLeaves().stream().filter(f -> f.kind() == kind && f.dimensions() == 0
                        && (kind != FieldSpec.Kind.PACKED || f.scale() >= 2))
                .findFirst().orElseThrow();
    }

    static byte[] paydbcr(String amount) {
        byte[] b = new byte[LAYOUT.length()];
        for (FieldSpec f : LAYOUT.canonicalLeaves()) {
            for (int i = 0; i < (f.dimensions() == 0 ? 1 : f.maxOccurs(0)); i++) {
                int at = f.offset() + (f.dimensions() == 0 ? 0 : i * f.stride(0));
                switch (f.kind()) {
                    case PACKED -> PackedDecimal.encode(BigDecimal.ZERO, b, at, f.digits(), f.scale(), f.signed());
                    case ZONED -> ZonedDecimal.encode(BigDecimal.ZERO, b, at, f.digits(), f.scale(), f.sign());
                    case ALPHANUMERIC -> Text.encode("", b, at, f.length(), LAYOUT.charset());
                    default -> { }
                }
            }
        }
        FieldSpec amt = first(FieldSpec.Kind.PACKED);
        PackedDecimal.encode(new BigDecimal(amount), b, amt.offset(), amt.digits(), amt.scale(), amt.signed());
        return b;
    }

    static Exchange payExchange(long seq, byte[] response) {
        return new Exchange(seq, "IMS", "-", "TESTPGM", "IMS:" + LAYOUT_KEY, "IMS:" + LAYOUT_KEY, "OK",
                paydbcr("0"), response);
    }

    @Test
    void tapeRoundTripsByteExact() throws Exception {
        List<Exchange> tape = List.of(payExchange(1, paydbcr("12.34")), payExchange(2, new byte[] {0, (byte) 0xFF}));
        StringWriter w = new StringWriter();
        Tape.write(w, tape);
        assertEquals(tape, Tape.read(new StringReader(w.toString())));
    }

    @Test
    void identicalReplayIsClean() {
        List<Exchange> legacy = List.of(payExchange(1, paydbcr("12.34")));
        List<Exchange> candidate = Replayer.replay(legacy, e -> Replayer.Result.ok(paydbcr("12.34")));
        ParityReport r = Replayer.compare(legacy, candidate);
        assertEquals(0, r.exitCode(), r.render());
        assertEquals(1, r.compared());
    }

    @Test
    void roundingInsteadOfTruncatingIsAComp3Blocker() {
        // Legacy COMPUTE without ROUNDED truncated 12.345 to 12.34; a candidate using HALF_UP stores 12.35.
        ParityReport r = Replayer.compare(List.of(payExchange(1, paydbcr("12.34"))),
                List.of(payExchange(1, paydbcr("12.35"))));
        assertEquals(ParityReport.RC_COMP3_BLOCKER, r.exitCode(), r.render());
        assertTrue(r.blocked());
        Divergence d = r.divergences().get(0);
        assertEquals(Divergence.Kind.COMP3, d.kind());
        assertEquals(first(FieldSpec.Kind.PACKED).path(), d.path());
        assertTrue(r.render().contains("BLOCKER"));
    }

    @Test
    void signNibbleOnlyDifferenceIsAComp3Blocker() {
        byte[] legacy = paydbcr("12.34");
        byte[] candidate = legacy.clone();
        FieldSpec amt = first(FieldSpec.Kind.PACKED);
        int last = amt.offset() + amt.length() - 1;
        candidate[last] = (byte) ((candidate[last] & 0xF0) | 0x0F); // C -> F: same value, different bytes
        ParityReport r = Replayer.compare(List.of(payExchange(1, legacy)), List.of(payExchange(1, candidate)));
        assertEquals(ParityReport.RC_COMP3_BLOCKER, r.exitCode(), r.render());
    }

    @Test
    void textDifferenceDivergesWithoutBlocking() {
        byte[] candidate = paydbcr("12.34");
        FieldSpec text = first(FieldSpec.Kind.ALPHANUMERIC);
        Text.encode("X", candidate, text.offset(), text.length(), LAYOUT.charset());
        ParityReport r = Replayer.compare(List.of(payExchange(1, paydbcr("12.34"))), List.of(payExchange(1, candidate)));
        assertEquals(ParityReport.RC_DIVERGED, r.exitCode(), r.render());
        assertEquals(Divergence.Kind.TEXT, r.divergences().get(0).kind());
    }

    @Test
    void outcomeAndMissingExchangesAreReported() {
        Exchange l = payExchange(1, paydbcr("1"));
        ParityReport r = Replayer.compare(List.of(l, payExchange(2, paydbcr("1"))),
                Replayer.replay(List.of(l), e -> { throw new IllegalStateException("boom"); }));
        assertEquals(Set.of(Divergence.Kind.OUTCOME, Divergence.Kind.MISSING, Divergence.Kind.LENGTH),
                Set.copyOf(r.divergences().stream().map(Divergence::kind).toList()));
    }

    @Test
    void creditAgencyRandomScoreIsVolatile() {
        byte[] a = new byte[Customer.LAYOUT.length()];
        byte[] b = a.clone();
        FieldSpec score = Customer.CUSTOMER_CREDIT_SCORE;
        ZonedDecimal.encode(new BigDecimal(123), a, score.offset(), score.digits(), score.scale(), score.sign());
        ZonedDecimal.encode(new BigDecimal(456), b, score.offset(), score.digits(), score.scale(), score.sign());
        Exchange l = new Exchange(1, "CICS", "OCR1", "CRDTAGY1", "CICS:CUSTOMER", "CICS:CUSTOMER", "OK", a, a);
        assertEquals(0, Replayer.compare(List.of(l), List.of(l.withResult(b, "OK"))).exitCode());
        Exchange other = new Exchange(1, "CICS", "-", "INQCUST", "CICS:CUSTOMER", "CICS:CUSTOMER", "OK", a, a);
        assertEquals(8, Replayer.compare(List.of(other), List.of(other.withResult(b, "OK"))).exitCode(),
                "outside CRDTAGY a numeric divergence blocks");
    }

    @Test
    void catalogCoversEveryOnlineProgramAndResolvesLayouts() throws Exception {
        Set<String> cics = new TreeSet<>();
        try (Stream<Path> s = Files.list(REPO.resolve("src/base/cics/cobol"))) {
            s.map(p -> p.getFileName().toString().replace(".cbl", "")).forEach(cics::add);
        }
        cics.removeAll(Set.of("ABNDPROC", "BANKDATA")); // abend logger, batch data generator
        Set<String> ims = new TreeSet<>();
        Matcher m = Pattern.compile("CREATE TRAN NAME\\((\\w+)\\)")
                .matcher(Files.readString(REPO.resolve(".setup/deploy/ims_spoc.jcl.j2")));
        while (m.find()) {
            ims.add(m.group(1));
        }
        for (String p : cics) {
            assertTrue(Catalog.find("CICS", p).isPresent(), "CICS " + p + " missing from catalog.tsv");
        }
        for (String t : ims) {
            assertTrue(Catalog.ENTRIES.stream().anyMatch(e -> e.system().equals("IMS") && e.transaction().equals(t)),
                    "IMS " + t + " missing from catalog.tsv");
        }
        for (Catalog.Entry e : Catalog.ENTRIES) {
            Layouts.resolve(e.requestLayout());
            Layouts.resolve(e.responseLayout());
            for (String v : e.volatileFields()) {
                assertNotNull(Layouts.resolve(e.responseLayout()).field(v));
            }
        }
    }

    static Snapshot snap(String balance, String name) {
        Snapshot s = new Snapshot();
        Snapshot.Table t = s.table("BANKZ.ACCOUNT", List.of(new Snapshot.Column("KEY", Snapshot.ColumnKind.TEXT),
                new Snapshot.Column("BAL", Snapshot.ColumnKind.DECIMAL), new Snapshot.Column("NAME", Snapshot.ColumnKind.TEXT)));
        t.put("1", List.of("1", balance, name));
        return s;
    }

    @Test
    void snapshotRoundTripsAndDiffs() throws Exception {
        Snapshot s = snap("10.00", "tab\there\nnewline\\x");
        s.tables().get("BANKZ.ACCOUNT").put("2", java.util.Arrays.asList("2", null, "n"));
        StringWriter w = new StringWriter();
        s.write(w);
        Snapshot back = Snapshot.read(new StringReader(w.toString()));
        StringWriter w2 = new StringWriter();
        back.write(w2);
        assertEquals(w.toString(), w2.toString());
        assertEquals(0, StateDiff.diff(s, back).exitCode());

        assertEquals(8, StateDiff.diff(snap("10.00", "a"), snap("10.01", "a")).exitCode());
        assertEquals(8, StateDiff.diff(snap("10.0", "a"), snap("10.00", "a")).exitCode(), "scale-only is COMP-3");
        assertEquals(4, StateDiff.diff(snap("10.00", "a"), snap("10.00", "b")).exitCode());
        assertEquals(0, StateDiff.diff(snap("10.00", "a"), snap("10.00", "b"), Set.of("BANKZ.ACCOUNT.NAME")).exitCode());
        assertEquals(8, StateDiff.diff(snap("1", "a"), snap("2", "a"), Set.of("BANKZ.ACCOUNT.BAL")).exitCode(),
                "DECIMAL columns cannot be ignored");
        Snapshot more = snap("10.00", "a");
        more.tables().get("BANKZ.ACCOUNT").put("9", List.of("9", "0.00", "z"));
        assertEquals(Divergence.Kind.ROW_ADDED, StateDiff.diff(snap("10.00", "a"), more).divergences().get(0).kind());
        assertEquals(Divergence.Kind.ROW_REMOVED, StateDiff.diff(more, snap("10.00", "a")).divergences().get(0).kind());
    }

    static String ddl(String table, Db2Table t, String extraColumn) {
        List<String> cols = new ArrayList<>();
        for (Db2Table.Column c : t.columns()) {
            String type = switch (c.type()) {
                case "CHAR" -> "CHAR(" + c.length() + ")";
                case "DECIMAL" -> "DECIMAL(" + c.length() + "," + c.scale() + ")";
                default -> c.type();
            };
            cols.add(c.name() + " " + type + (c.nullable() ? "" : " NOT NULL"));
        }
        if (extraColumn != null) {
            cols.add(extraColumn);
        }
        return "CREATE TABLE " + table + " (" + String.join(", ", cols) + ")";
    }

    @Test
    void db2SnapshotIsDeterministicAndChecksTheDclgen() throws Exception {
        try (Connection con = DriverManager.getConnection("jdbc:h2:mem:bankz;MODE=DB2");
             Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA BANKZ");
            st.execute(ddl("BANKZ.ACCOUNT", Accdb2.TABLE, null));
            st.execute("INSERT INTO BANKZ.ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_AVAILABLE_BALANCE,"
                    + " ACCOUNT_ACTUAL_BALANCE, ACCOUNT_OPENED) VALUES ('987654', '00000002', -5.5, 1, DATE '2024-01-31'),"
                    + " ('987654', '00000001', 100.25, 100.25, NULL)");
            Db2Snapshotter.TableSpec spec = Db2Snapshotter.BANKZ_TABLES.get(0);
            Snapshot a = Db2Snapshotter.snapshot(con, List.of(spec));
            Snapshot.Table t = a.tables().get("BANKZ.ACCOUNT");
            assertEquals(List.of("987654|00000001", "987654|00000002"), List.copyOf(t.rows().keySet()));
            int bal = Accdb2.TABLE.columnNames().indexOf("ACCOUNT_AVAILABLE_BALANCE");
            assertEquals("-5.50", t.rows().get("987654|00000002").get(bal));
            assertEquals(Snapshot.ColumnKind.DECIMAL, t.columns().get(bal).kind());

            st.execute("UPDATE BANKZ.ACCOUNT SET ACCOUNT_AVAILABLE_BALANCE = -5.49 WHERE ACCOUNT_NUMBER = '00000002'");
            ParityReport r = StateDiff.diff(a, Db2Snapshotter.snapshot(con, List.of(spec)));
            assertEquals(8, r.exitCode(), r.render());

            st.execute("ALTER TABLE BANKZ.ACCOUNT ADD COLUMN SURPRISE INTEGER");
            assertThrows(IllegalStateException.class, () -> Db2Snapshotter.snapshot(con, List.of(spec)));
        }
    }

    @Test
    void dclgenCopybooksMatchTheDdl() throws Exception {
        String ddl = Files.readString(REPO.resolve(".setup/jcl/cics/Db2-create.j2"));
        Matcher m = Pattern.compile("CREATE TABLE BANKZ\\.(\\w+) \\((.*?)\\n\\s*\\)", Pattern.DOTALL).matcher(ddl);
        int tables = 0;
        while (m.find()) {
            List<String> cols = new ArrayList<>();
            for (String line : m.group(2).split(",\\s*\\n")) {
                cols.add(line.trim().split("\\s+")[0]);
            }
            Db2Table declared = CicsCopybooks.TABLES.values().stream()
                    .filter(t -> t.name().replaceFirst("^\\w+\\.", "").equals(m.group(1))).findFirst().orElseThrow();
            assertEquals(cols, declared.columnNames(), m.group(1));
            tables++;
        }
        assertEquals(4, tables);
    }

    @Test
    void everyDbdParsesAndFieldsFitTheSegment() throws Exception {
        try (Stream<Path> s = Files.list(REPO.resolve("src/base/ims/DBD"))) {
            for (Path p : s.toList()) {
                Dbd dbd = Dbd.parse(p);
                assertEquals("Cp1047", dbd.encoding(), p.toString());
                for (Dbd.Segment seg : dbd.segments()) {
                    assertNotNull(seg.sequenceField(), seg.name());
                    for (Dbd.Field f : seg.fields()) {
                        assertTrue(f.offset() + f.bytes() <= seg.bytes(), seg.name() + "." + f.name());
                    }
                }
            }
        }
        Dbd account = Dbd.parse(REPO.resolve("src/base/ims/DBD/ACCOUNT.asm"));
        assertEquals(25, account.segment("ACCOUNT").bytes());
        assertEquals("ACCID", account.segment("ACCOUNT").sequenceField().name());
    }

    static byte[] accountSegment(long id, String balance) {
        byte[] b = new byte[25];
        Binary.encode(BigDecimal.valueOf(id), b, 0, 18, 0, true, true);
        Text.encode("S", b, 8, 1, Ebcdic.CP1047);
        PackedDecimal.encode(new BigDecimal(balance), b, 9, 15, 2, true);
        Binary.encode(BigDecimal.valueOf(7), b, 17, 18, 0, true, true);
        return b;
    }

    @Test
    void imsSegmentsDecodeWithTheDbdAndDiff() throws Exception {
        Dbd dbd = Dbd.parse(REPO.resolve("src/base/ims/DBD/ACCOUNT.asm"));
        byte[] unload = new byte[50];
        System.arraycopy(accountSegment(2, "-0.10"), 0, unload, 0, 25);
        System.arraycopy(accountSegment(1, "1234.56"), 0, unload, 25, 25);
        Snapshot a = new Snapshot();
        ImsSnapshotter.snapshot(dbd, "ACCOUNT", new ByteArrayInputStream(unload), a);
        Snapshot.Table t = a.tables().get("ACCOUNT/ACCOUNT");
        assertEquals(List.of("ACCID", "ACCTYPE", "BALANCE", "LASTTXID"), t.columns().stream().map(Snapshot.Column::name).toList());
        assertEquals(List.of("1", "S", "1234.56", "7"), t.rows().get("1"));
        assertEquals("-0.10", t.rows().get("2").get(2));

        unload[25 + 16] = (byte) 0x12; // invalid sign nibble in BALANCE of ACCID 1
        Snapshot b = new Snapshot();
        ImsSnapshotter.snapshot(dbd, "ACCOUNT", new ByteArrayInputStream(unload), b);
        assertTrue(b.tables().get("ACCOUNT/ACCOUNT").rows().get("1").get(2).startsWith("!HEX:"));
        assertEquals(8, StateDiff.diff(a, b).exitCode());
    }

    @Test
    void gateCommandLineReturnsJclStyleCodes(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("legacy.tape");
        Path candidate = dir.resolve("candidate.tape");
        Tape.write(legacy, List.of(payExchange(1, paydbcr("12.34"))));
        Tape.write(candidate, List.of(payExchange(1, paydbcr("12.34"))));
        assertEquals(0, ParityGate.run("compare-tapes", legacy.toString(), candidate.toString()));
        Tape.write(candidate, List.of(payExchange(1, paydbcr("12.35"))));
        assertEquals(8, ParityGate.run("compare-tapes", legacy.toString(), candidate.toString()));
        assertEquals(12, ParityGate.run("compare-tapes", legacy.toString()));

        Path a = dir.resolve("a.snap");
        Path b = dir.resolve("b.snap");
        snap("1.00", "x").write(a);
        snap("1.00", "y").write(b);
        assertEquals(4, ParityGate.run("diff-snapshots", a.toString(), b.toString()));
        assertEquals(0, ParityGate.run("diff-snapshots", a.toString(), b.toString(), "--ignore", "BANKZ.ACCOUNT.NAME"));

        Path unloads = Files.createDirectories(dir.resolve("unload"));
        Files.write(unloads.resolve("ACCOUNT.ACCOUNT.unload"), accountSegment(5, "3.00"));
        Path out = dir.resolve("ims.snap");
        assertEquals(0, ParityGate.run("snapshot-ims", REPO.resolve("src/base/ims/DBD").toString(),
                unloads.toString(), out.toString()));
        assertEquals("3.00", Snapshot.read(out).tables().get("ACCOUNT/ACCOUNT").rows().get("5").get(2));
    }

    @Test
    void reportDiffBlocksOnNumbers(@TempDir Path dir) throws Exception {
        assertEquals(0, ReportDiff.diff(List.of(" A 1.00"), List.of(" A 1.00")).exitCode());
        assertEquals(4, ReportDiff.diff(List.of(" A 1.00"), List.of(" B 1.00")).exitCode());
        assertEquals(8, ReportDiff.diff(List.of(" A 1.00"), List.of(" A 1.01")).exitCode());
        assertEquals(4, ReportDiff.diff(List.of(" A"), List.of(" A", " B")).exitCode());
        Path a = Files.writeString(dir.resolve("a"), "1X\n Y 2\n");
        Path b = Files.writeString(dir.resolve("b"), "1X\n Y 3\n");
        assertEquals(8, ParityGate.run("compare-reports", a.toString(), b.toString()));
    }
}
