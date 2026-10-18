package com.ibm.bankofz.migration.goldenmaster;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JsonDiffTest {

    private static JsonNode json(String s) throws Exception {
        return RepoFiles.JSON.readTree(s);
    }

    @Test
    void numbersCompareByDecimalValue() throws Exception {
        assertEquals(List.of(), JsonDiff.diff(json("{\"a\":50.0}"), json("{\"a\":50.00}"), Set.of()));
        assertEquals(List.of("/a: expected 0.3 but was 0.3000000000000001"),
                JsonDiff.diff(json("{\"a\":0.30}"), json("{\"a\":0.3000000000000001}"), Set.of()));
    }

    @Test
    void reportsMissingExtraAndTypeChanges() throws Exception {
        List<String> d = JsonDiff.diff(json("{\"a\":\"1\",\"b\":[1,2]}"), json("{\"a\":1,\"b\":[1],\"c\":true}"), Set.of());
        assertEquals(List.of(
                "/a: expected STRING \"1\" but was NUMBER 1",
                "/b: expected 2 elements but was 1",
                "/c: unexpected true"), d);
    }

    @Test
    void ignoresVolatilePathsWithWildcards() throws Exception {
        JsonNode e = json("{\"dateTime\":\"x\",\"t\":[{\"v\":\"1\",\"k\":1},{\"v\":\"2\",\"k\":2}]}");
        JsonNode a = json("{\"dateTime\":\"y\",\"t\":[{\"v\":\"9\",\"k\":1},{\"v\":\"8\",\"k\":2}]}");
        assertEquals(List.of(), JsonDiff.diff(e, a, Set.of("/dateTime", "/t/*/v")));
        assertEquals(2, JsonDiff.diff(e, a, Set.of("/dateTime")).size());
    }
}
