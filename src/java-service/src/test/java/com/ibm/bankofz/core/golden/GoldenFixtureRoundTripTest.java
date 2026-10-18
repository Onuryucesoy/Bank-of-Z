package com.ibm.bankofz.core.golden;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.core.RepoPaths;
import com.ibm.bankofz.core.copybook.CopybookParser;
import com.ibm.bankofz.core.copybook.RecordCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Phase 0 golden-master harness. Today it proves every fixture is
 * well-formed against the frozen copybook and survives
 * fields -&gt; EBCDIC bytes -&gt; fields unchanged. In Phase 2 the same
 * comparator is pointed at Java service output (and at bytes captured from
 * CICS) instead of the re-encoded fixture.
 */
class GoldenFixtureRoundTripTest {

    static final Set<String> REQUIRED = Set.of("INQCUST", "INQACC", "CRECUST", "DELCUS", "XFRFUN", "DBCRFUN",
            "UPDCUST", "CRDTAGY1", "CRDTAGY2", "CRDTAGY3", "CRDTAGY4", "CRDTAGY5");

    static Stream<Path> fixtures() throws IOException {
        return Files.list(RepoPaths.resolve("tests/golden/fixtures")).filter(p -> p.toString().endsWith(".json")).sorted();
    }

    @Test
    void everyInventoriedTransactionHasAFixture() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path p : fixtures().toList()) {
            found.add(GoldenFixture.read(p).transaction());
        }
        assertTrue(found.containsAll(REQUIRED), "missing fixtures: " + new TreeSet<>(REQUIRED) + " vs " + found);
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void fixtureRoundTripsThroughCopybookLayout(Path file) throws IOException {
        GoldenFixture fx = GoldenFixture.read(file);
        assertEquals(file.getFileName().toString(), fx.transaction() + ".json");
        assertEquals("HAND_AUTHORED_UNVALIDATED", fx.baseline().status(), "baseline status must be explicit");
        assertEquals(37, fx.ccsid());
        assertTrue(Files.exists(RepoPaths.resolve(fx.program())), fx.program());
        assertFalse(fx.cases().isEmpty());

        RecordCodec codec = new RecordCodec(
                CopybookParser.parse(RepoPaths.resolve(fx.copybook())).record(fx.record()));
        GoldenMasterComparator cmp = new GoldenMasterComparator(codec);
        for (GoldenFixture.Case c : fx.cases()) {
            for (Map<String, String> side : List.of(c.request(), c.response())) {
                byte[] bytes = codec.encode(side);
                assertEquals(codec.length(), bytes.length, c.name());
                List<GoldenMasterComparator.Mismatch> diff = cmp.compare(side, bytes, List.of());
                assertTrue(diff.isEmpty(), fx.transaction() + "/" + c.name() + ": " + diff);
                byte[] again = codec.encode(stripRedefines(codec, codec.decode(bytes)));
                assertArrayEquals(bytes, again, fx.transaction() + "/" + c.name() + " is not byte-stable");
            }
            for (String nd : c.nonDeterministicOrEmpty()) {
                assertTrue(codec.slots().containsKey(nd), "unknown non-deterministic field " + nd);
            }
        }
    }

    @Test
    void comparatorPinpointsTheDifferingField() throws IOException {
        GoldenFixture fx = GoldenFixture.read(RepoPaths.resolve("tests/golden/fixtures/INQACC.json"));
        RecordCodec codec = new RecordCodec(
                CopybookParser.parse(RepoPaths.resolve(fx.copybook())).record(fx.record()));
        GoldenMasterComparator cmp = new GoldenMasterComparator(codec);
        Map<String, String> expected = fx.cases().get(0).response();
        byte[] actual = codec.encode(expected);
        actual[85] = (byte) 0xD6; // last byte of INQACC-AVAIL-BAL: flip sign zone to negative

        List<GoldenMasterComparator.Mismatch> fields = cmp.compare(expected, actual, List.of());
        assertEquals(1, fields.size(), fields.toString());
        assertEquals("INQACC-AVAIL-BAL", fields.get(0).field());
        assertEquals("-1234.56", fields.get(0).actual());

        List<GoldenMasterComparator.Mismatch> bytes = cmp.compareBytes(codec.encode(expected), actual);
        assertEquals("INQACC-AVAIL-BAL", bytes.get(0).field());
        assertEquals(85, bytes.get(0).offset());
    }

    private static Map<String, String> stripRedefines(RecordCodec codec, Map<String, String> decoded) {
        decoded.keySet().removeIf(k -> codec.slots().get(k).field().underRedefines());
        return decoded;
    }
}
