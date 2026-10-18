package com.ibm.bankofz.migration.goldenmaster;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Structural JSON diff used for golden-master comparison. Numbers are compared by decimal value
 * ({@code 50.0 == 50.00}), never through double. Paths in {@code ignore} are JSON Pointers; a trailing
 * {@code *} segment matches any single array index or property name (e.g. {@code /dateTime},
 * {@code /transactions/STAR/valueDateTime} with STAR replaced by an asterisk).
 */
final class JsonDiff {

    private JsonDiff() {
    }

    static List<String> diff(JsonNode expected, JsonNode actual, Set<String> ignore) {
        List<String> out = new ArrayList<>();
        walk("", expected, actual, ignore, out);
        return out;
    }

    private static void walk(String path, JsonNode e, JsonNode a, Set<String> ignore, List<String> out) {
        if (ignored(path, ignore)) {
            return;
        }
        if (e == null || e.isMissingNode()) {
            out.add(path + ": unexpected " + a);
            return;
        }
        if (a == null || a.isMissingNode()) {
            out.add(path + ": missing (expected " + e + ")");
            return;
        }
        if (e.isNumber() && a.isNumber()) {
            if (e.decimalValue().compareTo(a.decimalValue()) != 0) {
                out.add(path + ": expected " + e.decimalValue().toPlainString() + " but was " + a.decimalValue().toPlainString());
            }
            return;
        }
        if (e.getNodeType() != a.getNodeType()) {
            out.add(path + ": expected " + e.getNodeType() + " " + e + " but was " + a.getNodeType() + " " + a);
            return;
        }
        if (e.isObject()) {
            Set<String> names = new TreeSet<>();
            e.fieldNames().forEachRemaining(names::add);
            a.fieldNames().forEachRemaining(names::add);
            for (String n : names) {
                walk(path + "/" + n.replace("~", "~0").replace("/", "~1"), e.get(n), a.get(n), ignore, out);
            }
        } else if (e.isArray()) {
            if (e.size() != a.size()) {
                out.add(path + ": expected " + e.size() + " elements but was " + a.size());
            }
            Iterator<JsonNode> ei = e.elements();
            Iterator<JsonNode> ai = a.elements();
            for (int i = 0; ei.hasNext() && ai.hasNext(); i++) {
                walk(path + "/" + i, ei.next(), ai.next(), ignore, out);
            }
        } else if (!e.equals(a)) {
            out.add(path + ": expected " + e + " but was " + a);
        }
    }

    private static boolean ignored(String path, Set<String> ignore) {
        if (ignore.contains(path)) {
            return true;
        }
        for (String pattern : ignore) {
            if (pattern.contains("*") && path.matches(pattern.replace("*", "[^/]+").replace("(", "\\(") + "(/.*)?")) {
                return true;
            }
        }
        return false;
    }
}
