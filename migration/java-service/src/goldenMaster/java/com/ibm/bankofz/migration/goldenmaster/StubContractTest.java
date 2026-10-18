package com.ibm.bankofz.migration.goldenmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Phase 0 executable contract: every operation in openapi.yaml is served by the Java stub with its declared
 * success status, a body valid against the declared schema, and the zosAsset recorded in operation-inventory.json.
 */
class StubContractTest {

    private static final Set<String> METHODS = Set.of("get", "post", "put", "delete", "patch");
    private static StubServer server;

    @BeforeAll
    static void start() {
        server = StubServer.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    record Op(String method, String path, JsonNode spec) {
        String successStatus() {
            for (String s : List.of("200", "201", "204")) {
                if (spec.path("responses").has(s)) {
                    return s;
                }
            }
            throw new IllegalStateException(method + " " + path + " declares no 2xx response");
        }

        String ref(JsonNode content) {
            String r = content.path("content").path("application/json").path("schema").path("$ref").asText(null);
            return r == null ? null : r.substring(r.lastIndexOf('/') + 1);
        }

        String concretePath() {
            String p = path;
            for (JsonNode param : spec.path("parameters")) {
                if ("path".equals(param.path("in").asText())) {
                    p = p.replace("{" + param.path("name").asText() + "}", param.path("schema").path("example").asText("1"));
                }
            }
            return p;
        }
    }

    static List<Op> operations() {
        List<Op> ops = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> paths = Schemas.openApi().path("paths").fields();
        paths.forEachRemaining(p -> p.getValue().fields().forEachRemaining(m -> {
            if (METHODS.contains(m.getKey())) {
                ops.add(new Op(m.getKey().toUpperCase(), p.getKey(), m.getValue()));
            }
        }));
        return ops;
    }

    static Map<String, String> inventoryAssets() {
        Map<String, String> byCall = new HashMap<>();
        for (JsonNode asset : RepoFiles.readJson(RepoFiles.INVENTORY).path("operations")) {
            for (JsonNode api : asset.path("apiOperations")) {
                byCall.put(api.path("method").asText() + " " + api.path("path").asText(), asset.path("operation").asText());
            }
        }
        return byCall;
    }

    /** Minimal valid request bodies, built from the schema's {@code example} values. */
    static JsonNode sampleBody(String schemaName) {
        if (schemaName == null) {
            return null;
        }
        JsonNode schema = Schemas.openApi().path("components").path("schemas").path(schemaName);
        ObjectNode body = RepoFiles.JSON.createObjectNode();
        schema.path("properties").fields().forEachRemaining(e -> {
            JsonNode example = e.getValue().path("example");
            if (!example.isMissingNode()) {
                body.set(e.getKey(), example);
            }
        });
        return body;
    }

    @Test
    void contractHasSeventeenOperations() {
        assertEquals(17, operations().size());
        assertEquals(17, inventoryAssets().size());
    }

    @TestFactory
    Stream<DynamicTest> everyOpenApiOperationIsStubbed() {
        Map<String, String> assets = inventoryAssets();
        return operations().stream().map(op -> dynamicTest(op.method() + " " + op.path() + " (" + op.spec().path("operationId").asText() + ")", () -> {
            JsonNode body = sampleBody(op.ref(op.spec().path("requestBody")));
            StubServer.Reply r = server.call(op.method(), op.concretePath(), body);
            assertEquals(Integer.parseInt(op.successStatus()), r.status(), "status");
            assertEquals(assets.get(op.method() + " " + op.path()), r.header("X-BankOfZ-Backend"), "backend zosAsset");
            String schema = op.ref(op.spec().path("responses").path(op.successStatus()));
            if (schema == null) {
                assertNull(r.body());
            } else {
                assertNotNull(r.body());
                assertEquals(List.of(), Schemas.errors(Schemas.openApiComponent(schema), r.body()));
            }
        }));
    }

    @Test
    void invalidDepositIsRejectedWithErrorSchema() {
        ObjectNode body = RepoFiles.JSON.createObjectNode().put("amount", 0).put("sortCode", "12");
        StubServer.Reply r = server.call("POST", "/accounts/3000/deposit", body);
        assertEquals(400, r.status());
        assertEquals(List.of(), Schemas.errors(Schemas.openApiComponent("Error"), r.body()));
    }
}
