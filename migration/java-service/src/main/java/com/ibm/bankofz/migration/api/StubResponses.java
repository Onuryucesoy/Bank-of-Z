package com.ibm.bankofz.migration.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canned OpenAPI-shaped responses keyed by {@code operationId}, loaded from
 * {@code /stubs/openapi-stub-responses.json}. Phase 2 replaces these with real backend adapters.
 */
public final class StubResponses {

    public static final String BACKEND_HEADER = "X-BankOfZ-Backend";
    public static final String STUB_HEADER = "X-BankOfZ-Stub";
    private static final String RESOURCE = "/stubs/openapi-stub-responses.json";

    private final Map<String, JsonNode> byOperationId;

    public StubResponses(Map<String, JsonNode> byOperationId) {
        this.byOperationId = new TreeMap<>(byOperationId);
    }

    public static StubResponses loadDefault() {
        try (InputStream in = StubResponses.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(in, RESOURCE + " missing from classpath");
            JsonNode root = BankOfZApplication.MAPPER.readTree(in);
            Map<String, JsonNode> map = new TreeMap<>();
            root.fields().forEachRemaining(e -> {
                if (!e.getKey().startsWith("_")) {
                    map.put(e.getKey(), e.getValue());
                }
            });
            return new StubResponses(map);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, JsonNode> all() {
        return Map.copyOf(byOperationId);
    }

    /** Deep copy of the canned body so callers can personalise it (e.g. echo path ids). */
    public ObjectNode body(String operationId) {
        JsonNode node = byOperationId.get(operationId);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("No stub response for operationId " + operationId);
        }
        return node.deepCopy();
    }

    public static Response ok(int status, ZosAsset asset, Object body) {
        Response.ResponseBuilder rb = Response.status(status).header(BACKEND_HEADER, asset.name()).header(STUB_HEADER, "true");
        return body == null ? rb.build() : rb.entity(body).type(MediaType.APPLICATION_JSON_TYPE).build();
    }

    public static Response error(int status, String code, String message) {
        ObjectNode err = BankOfZApplication.MAPPER.createObjectNode().put("code", code).put("message", message);
        return Response.status(status).entity(err).type(MediaType.APPLICATION_JSON_TYPE).header(STUB_HEADER, "true").build();
    }
}
