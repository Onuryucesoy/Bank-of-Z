package com.ibm.bankofz.migration.goldenmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Replays captured production request→response pairs against the Java service and diffs the bodies.
 *
 * <p>Placeholder until Phase 2: drop recordings into {@code migration/golden-master/recordings/<ASSET>/*.json}
 * (format in {@code migration/golden-master/README.md}). With no recordings the test is reported as skipped.
 * Point at another implementation with {@code -Dbankofz.replay.target=http://host:port/api}.
 */
class GoldenMasterReplayTest {

    private static StubServer server;

    @BeforeAll
    static void start() {
        server = StubServer.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    static List<Path> recordings() throws IOException {
        if (!Files.isDirectory(RepoFiles.RECORDINGS)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(RepoFiles.RECORDINGS)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @TestFactory
    Stream<DynamicTest> replayCapturedTraffic() throws IOException {
        List<Path> recs = recordings();
        if (recs.isEmpty()) {
            return Stream.of(dynamicTest("no z/OS Connect recordings captured yet", () ->
                    assumeTrue(false, "add recordings under " + RepoFiles.ROOT.relativize(RepoFiles.RECORDINGS))));
        }
        return recs.stream().map(p -> dynamicTest(RepoFiles.RECORDINGS.relativize(p).toString(), () -> {
            JsonNode rec = RepoFiles.readJson(p);
            JsonNode req = rec.path("request");
            JsonNode expected = rec.path("response");
            Set<String> ignore = new TreeSet<>();
            rec.path("ignore").forEach(n -> ignore.add(n.asText()));
            JsonNode body = req.path("body").isMissingNode() || req.path("body").isNull() ? null : req.path("body");
            StubServer.Reply actual = server.call(req.path("method").asText(), req.path("path").asText(), body);
            assertEquals(expected.path("status").asInt(), actual.status(), "HTTP status");
            JsonNode expectedBody = expected.path("body");
            if (!expectedBody.isMissingNode() && !expectedBody.isNull()) {
                assertEquals(List.of(), JsonDiff.diff(expectedBody, actual.body(), ignore));
            }
        }));
    }
}
