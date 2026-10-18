package com.ibm.bankofz.migration.goldenmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.ibm.bankofz.migration.api.ZosAsset;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Phase 0: every generated z/OS Connect schema loads, and its canonical skeleton validates against it. */
class ZosAssetSchemaTest {

    @Test
    void zosAssetEnumMatchesRepositoryAndInventory() throws IOException {
        List<String> onDisk;
        try (Stream<Path> dirs = Files.list(RepoFiles.ZOS_ASSETS)) {
            onDisk = dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        }
        List<String> inEnum = Arrays.stream(ZosAsset.values()).map(Enum::name).sorted().toList();
        assertEquals(onDisk, inEnum);

        JsonNode inventory = RepoFiles.readJson(RepoFiles.INVENTORY);
        assertEquals(onDisk.size(), inventory.path("counts").path("zosAssets").asInt());
        for (JsonNode op : inventory.path("operations")) {
            ZosAsset asset = ZosAsset.valueOf(op.path("operation").asText());
            assertEquals(asset.backend().name(), op.path("backend").asText(), asset + " backend");
            assertTrue(Files.exists(RepoFiles.ROOT.resolve(op.path("requestSchema").asText())));
            assertTrue(Files.exists(RepoFiles.ROOT.resolve(op.path("responseSchema").asText())));
            assertTrue(Files.exists(RepoFiles.ROOT.resolve(op.path("cobolProgram").asText())), asset + " COBOL program");
        }
    }

    @TestFactory
    Stream<DynamicTest> skeletonOfEveryGeneratedSchemaIsValid() {
        return Arrays.stream(ZosAsset.values()).flatMap(asset -> Stream.of("request", "response").map(kind ->
                dynamicTest(asset + " " + kind + "Schema.json", () -> {
                    JsonNode schema = RepoFiles.readJson(RepoFiles.generatedSchema(asset.name(), kind));
                    assertEquals("http://json-schema.org/draft-04/schema#", schema.path("$schema").asText());
                    JsonNode skeleton = SchemaSkeleton.of(schema);
                    assertFalse(skeleton.isEmpty(), "skeleton has properties");
                    assertEquals(List.of(), Schemas.errors(Schemas.zosAsset(asset.name(), kind), skeleton));
                })));
    }

    @Test
    void schemasRejectValuesThatDoNotFitThePicClause() {
        JsonNode schema = RepoFiles.readJson(RepoFiles.generatedSchema("INQACC", "response"));
        JsonNode bad = SchemaSkeleton.of(schema);
        ((com.fasterxml.jackson.databind.node.ObjectNode) bad.path("commarea").path("INQACC-COMMAREA"))
                .put("INQACC-ACC-TYPE", "TOO-LONG-TYPE")
                .put("INQACC-AVAIL-BAL", new java.math.BigDecimal("12345678901.00"));
        assertEquals(2, Schemas.errors(Schemas.zosAsset("INQACC", "response"), bad).size());
    }
}
