package com.ibm.bankofz.migration.goldenmaster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;

/**
 * Builds the canonical "empty COMMAREA" instance of a generated z/OS Connect schema: every property present,
 * strings blank, numbers zero (BigDecimal), arrays at {@code minItems}. This is what an all-spaces/all-zeros
 * record looks like after z/OS Connect's data transformation, and is the base canned fixture for stubs.
 */
final class SchemaSkeleton {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SchemaSkeleton() {
    }

    static JsonNode of(JsonNode schema) {
        return switch (schema.path("type").asText()) {
            case "object" -> {
                ObjectNode o = NODES.objectNode();
                schema.path("properties").fields().forEachRemaining(e -> {
                    if (!"abendCode".equals(e.getKey())) {
                        o.set(e.getKey(), of(e.getValue()));
                    }
                });
                yield o;
            }
            case "array" -> {
                ArrayNode a = NODES.arrayNode();
                for (int i = 0; i < schema.path("minItems").asInt(1); i++) {
                    a.add(of(schema.path("items")));
                }
                yield a;
            }
            case "string" -> NODES.textNode("");
            case "integer" -> NODES.numberNode(BigDecimal.ZERO.toBigInteger());
            case "number" -> NODES.numberNode(BigDecimal.ZERO);
            case "boolean" -> NODES.booleanNode(false);
            default -> NODES.nullNode();
        };
    }
}
