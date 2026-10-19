package com.ibm.bankofz.bridge.gen;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fails when a copybook changed but the committed bridge classes were not regenerated (or vice versa). */
class CopybookDriftTest {
    @Test
    void committedClassesMatchCopybooks() throws Exception {
        Path repo = Path.of(System.getProperty("bankz.repoRoot"));
        Path src = repo.resolve("src/base/java/copybook-bridge/src/main/java");
        Map<String, String> expected = Generate.generate(repo);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Path p = src.resolve(e.getKey());
            if (!Files.exists(p)) {
                problems.add("missing " + e.getKey());
            } else if (!Files.readString(p, StandardCharsets.UTF_8).equals(e.getValue())) {
                problems.add("stale " + e.getKey());
            }
        }
        try (Stream<Path> s = Files.walk(src.resolve("com/ibm/bankofz/bridge/copybook"))) {
            s.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> src.relativize(p).toString().replace('\\', '/'))
                    .filter(rel -> !expected.containsKey(rel))
                    .forEach(rel -> problems.add("orphan " + rel));
        }
        assertTrue(problems.isEmpty(), "run :copybook-bridge:generateCopybooks -> " + problems);
        assertEquals(55, expected.size());
    }
}
