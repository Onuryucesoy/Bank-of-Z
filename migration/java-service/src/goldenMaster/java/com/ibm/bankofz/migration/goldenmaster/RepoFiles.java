package com.ibm.bankofz.migration.goldenmaster;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates repository files; Gradle passes the repo root as the {@code bankofz.repoRoot} system property. */
final class RepoFiles {

    static final ObjectMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    static final Path ROOT = Path.of(System.getProperty("bankofz.repoRoot", "../..")).toAbsolutePath().normalize();
    static final Path OPENAPI = ROOT.resolve("src/api/src/main/api/openapi.yaml");
    static final Path ZOS_ASSETS = ROOT.resolve("src/api/src/main/zosAssets");
    static final Path INVENTORY = ROOT.resolve("migration/operation-inventory.json");
    static final Path FIXTURES = ROOT.resolve("migration/golden-master/fixtures");
    static final Path RECORDINGS = ROOT.resolve("migration/golden-master/recordings");

    private RepoFiles() {
    }

    static JsonNode readJson(Path p) {
        try {
            return JSON.readTree(Files.readString(p));
        } catch (IOException e) {
            throw new UncheckedIOException(p.toString(), e);
        }
    }

    static Path generatedSchema(String asset, String kind) {
        return ZOS_ASSETS.resolve(asset).resolve("providerFiles/gen").resolve(kind + "Schema.json");
    }
}
