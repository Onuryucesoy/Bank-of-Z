package com.ibm.bankofz.migration.goldenmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the two contracts the migration must honour:
 * <ul>
 *   <li>z/OS Connect generated draft-04 schemas ({@code zosAssets/<ASSET>/providerFiles/gen/*Schema.json}) — the
 *       COMMAREA / IMS message shape a Java backend adapter must produce byte-for-byte equivalent JSON for;</li>
 *   <li>OpenAPI 3.0 component schemas ({@code openapi.yaml}) — the external REST shape. They are rewritten into a
 *       draft-04 document ({@code #/components/schemas/X → #/definitions/X}); OpenAPI-only keywords such as
 *       {@code example} are ignored by the validator.</li>
 * </ul>
 */
final class Schemas {

    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V4);
    private static final SchemaValidatorsConfig CONFIG = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    private static final Map<String, JsonSchema> CACHE = new ConcurrentHashMap<>();
    private static volatile JsonNode openApi;

    private Schemas() {
    }

    static JsonNode openApi() {
        if (openApi == null) {
            try {
                openApi = new YAMLMapper().readTree(Files.readString(RepoFiles.OPENAPI));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return openApi;
    }

    static JsonSchema zosAsset(String asset, String kind) {
        Path p = RepoFiles.generatedSchema(asset, kind);
        return CACHE.computeIfAbsent(p.toString(), k -> FACTORY.getSchema(RepoFiles.readJson(p), CONFIG));
    }

    static JsonSchema openApiComponent(String name) {
        return CACHE.computeIfAbsent("openapi:" + name, k -> {
            JsonNode components = openApi().path("components").path("schemas");
            if (!components.has(name)) {
                throw new IllegalArgumentException("openapi.yaml has no component schema " + name);
            }
            String defs = components.toString().replace("#/components/schemas/", "#/definitions/");
            ObjectNode doc = RepoFiles.JSON.createObjectNode();
            doc.put("$schema", "http://json-schema.org/draft-04/schema#");
            doc.put("$ref", "#/definitions/" + name);
            try {
                doc.set("definitions", RepoFiles.JSON.readTree(defs));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return FACTORY.getSchema(doc, CONFIG);
        });
    }

    static List<String> errors(JsonSchema schema, JsonNode instance) {
        return schema.validate(instance).stream().map(ValidationMessage::getMessage).sorted().toList();
    }
}
