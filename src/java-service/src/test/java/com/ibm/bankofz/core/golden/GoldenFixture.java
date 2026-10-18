package com.ibm.bankofz.core.golden;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** JSON shape of tests/golden/fixtures/*.json (documented in tests/golden/README.md). */
public record GoldenFixture(
        String transaction,
        String program,
        String iface,
        String copybook,
        String record,
        int ccsid,
        Baseline baseline,
        String channel,
        String container,
        String transid,
        List<Case> cases) {

    public record Baseline(String status, String note, List<String> derivedFrom) {
    }

    public record Case(String name, String description, Map<String, String> request, Map<String, String> response,
                       List<String> nonDeterministic) {
        public List<String> nonDeterministicOrEmpty() {
            return nonDeterministic == null ? List.of() : nonDeterministic;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public static GoldenFixture read(Path file) throws IOException {
        Map<?, ?> raw = JSON.readValue(file.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> m = new java.util.LinkedHashMap<>((Map<String, Object>) raw);
        m.put("iface", m.remove("interface"));
        return JSON.convertValue(m, GoldenFixture.class);
    }
}
