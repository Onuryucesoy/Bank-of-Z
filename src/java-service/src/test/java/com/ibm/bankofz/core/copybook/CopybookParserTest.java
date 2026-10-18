package com.ibm.bankofz.core.copybook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.bankofz.core.RepoPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class CopybookParserTest {

    static Stream<Path> allCopybooks() throws IOException {
        List<String> dirs = List.of("src/base/cics/copy", "src/base/ims/copy", "src/api/src/main/zosAssets");
        return dirs.stream().flatMap(d -> {
            try {
                // JNI.cpy is IBM's Java-interop copybook (procedure pointers), not a data contract.
                return Files.walk(RepoPaths.resolve(d))
                        .filter(p -> p.toString().endsWith(".cpy") && !p.endsWith("JNI.cpy")).sorted();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @ParameterizedTest
    @MethodSource("allCopybooks")
    void everyRepositoryCopybookParsesAndRoundTripsDefaults(Path file) throws IOException {
        Copybook cb = CopybookParser.parse(file);
        if (cb.records().isEmpty()) {
            return; // SQL-only copybook (EXEC SQL DECLARE TABLE) or constants
        }
        for (Field rec : cb.records()) {
            RecordCodec codec = new RecordCodec(rec);
            byte[] bytes = codec.encode(java.util.Map.of());
            assertEquals(rec.length(), bytes.length);
            assertFalse(codec.decode(bytes).isEmpty() && !codec.slots().isEmpty()
                    && codec.slots().values().stream().anyMatch(s -> !s.field().isFiller()
                    && s.field().usage() != Usage.POINTER), rec.name());
            System.out.printf("LAYOUT %s %s %d%n", cb.name(), rec.name(), rec.length());
        }
    }

    @ParameterizedTest
    @CsvSource({
        "src/base/cics/copy/CUSTOMER.cpy, 397",
        "src/base/cics/copy/ACCOUNT.cpy, 98",
        "src/base/cics/copy/PROCTRAN.cpy, 99",
    })
    void coreRecordLengthsMatchHandComputedLayouts(String path, int length) throws IOException {
        assertEquals(length, CopybookParser.parse(RepoPaths.resolve(path)).primary().length());
    }

    @Test
    void packedAndBinaryUsagesAreRecognised() {
        Copybook cb = CopybookParser.parse("T", List.of(
                "       01 T.",
                "          05 A PIC S9(10)V99 COMP-3.",
                "          05 B PIC S9(9) COMP-5.",
                "          05 C PIC 9(4) COMP.",
                "          05 D PIC X(3) VALUE 'A.B'.",
                "          05 E REDEFINES D PIC 9(3).",
                "          05 F OCCURS 2 TIMES.",
                "             10 G PIC 9."));
        Field t = cb.primary();
        assertEquals(7 + 4 + 2 + 3 + 2, t.length());
        assertTrue(Copybook.slots(t).containsKey("G(2)"));
        assertEquals(Usage.PACKED, t.children().get(0).usage());
    }
}
