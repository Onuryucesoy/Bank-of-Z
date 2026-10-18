package com.ibm.bankofz.migration.goldenmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase 2 wave-1 fixtures ({@code migration/golden-master/fixtures/<ASSET>/}): the zosAsset request/response
 * validate against the generated schemas, the API body validates against openapi.yaml, and the Java stub
 * returns exactly that API body for the recorded call.
 */
class GoldenMasterFixtureTest {

    static final List<String> WAVE_1 = List.of("INQCUST", "INQACC", "INQACCS", "INQTRAND", "INQTRANL");
    private static StubServer server;

    @BeforeAll
    static void start() {
        server = StubServer.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    @Test
    void everyWaveOneOperationHasFixtures() {
        for (String asset : WAVE_1) {
            for (String f : List.of("manifest.json", "request.json", "response.json", "api-response.json")) {
                assertTrue(Files.exists(RepoFiles.FIXTURES.resolve(asset).resolve(f)), asset + "/" + f);
            }
        }
    }

    @TestFactory
    Stream<DynamicContainer> fixturesHonourBothContracts() {
        return WAVE_1.stream().map(asset -> {
            Path dir = RepoFiles.FIXTURES.resolve(asset);
            JsonNode manifest = RepoFiles.readJson(dir.resolve("manifest.json"));
            JsonNode call = manifest.path("apiCall");
            JsonNode api = RepoFiles.readJson(dir.resolve("api-response.json"));
            return DynamicContainer.dynamicContainer(asset, Stream.of(
                    dynamicTest("request.json matches requestSchema.json", () -> assertEquals(List.of(),
                            Schemas.errors(Schemas.zosAsset(asset, "request"), RepoFiles.readJson(dir.resolve("request.json"))))),
                    dynamicTest("response.json matches responseSchema.json", () -> assertEquals(List.of(),
                            Schemas.errors(Schemas.zosAsset(asset, "response"), RepoFiles.readJson(dir.resolve("response.json"))))),
                    dynamicTest("api-response.json matches openapi " + call.path("schema").asText(), () -> assertEquals(List.of(),
                            Schemas.errors(Schemas.openApiComponent(call.path("schema").asText()), api))),
                    dynamicTest("stub " + call.path("method").asText() + " " + call.path("path").asText() + " == api-response.json", () -> {
                        StubServer.Reply r = server.call(call.path("method").asText(), call.path("path").asText(), null);
                        assertEquals(call.path("status").asInt(), r.status());
                        assertEquals(asset, r.header("X-BankOfZ-Backend"));
                        assertEquals(List.of(), JsonDiff.diff(api, r.body(), Set.of()));
                    })));
        });
    }
}
