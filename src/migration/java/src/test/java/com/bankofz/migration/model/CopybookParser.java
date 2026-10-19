package com.bankofz.migration.model;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal fixed-format COBOL copybook reader for the conformance tests. */
final class CopybookParser {

    record Item(int level, String name, String pic, Usage usage, String redefines, String value,
                List<Item> children) {

        boolean isGroup() {
            return pic == null && level != 88;
        }

        List<Item> conditions() {
            return children.stream().filter(c -> c.level == 88).toList();
        }

        List<Item> fields() {
            return children.stream().filter(c -> c.level != 88).toList();
        }

        /** Total DISPLAY digits of a numeric group (e.g. a DD/MM/YYYY date group). */
        int numericGroupDigits() {
            int total = 0;
            for (Item c : fields()) {
                if (c.pic == null) {
                    total += c.numericGroupDigits();
                } else {
                    PicClause p = PicClause.parse(c.pic);
                    if (p.alphanumeric() || p.scale() != 0 || p.signed() || c.usage != Usage.DISPLAY) {
                        throw new IllegalStateException(name + " is not an unsigned numeric group");
                    }
                    total += p.length();
                }
            }
            return total;
        }
    }

    static final Path REPO_ROOT = Path.of(System.getProperty("bankofz.repoRoot", "../../.."));

    private static final Pattern VALUE = Pattern.compile("(?<![\\w-])VALUE\\s+('([^']*)'|\\S+)");

    private final Map<String, Item> byName = new LinkedHashMap<>();

    static CopybookParser read(String repoRelativePath) {
        try {
            return new CopybookParser(Files.readAllLines(REPO_ROOT.resolve(repoRelativePath)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copybook text in the COBOL area (columns 8-72), comment lines removed. */
    static String sourceText(String repoRelativePath) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(REPO_ROOT.resolve(repoRelativePath))) {
                if (line.length() > 6 && line.charAt(6) == '*') {
                    continue;
                }
                if (line.length() > 7) {
                    sb.append(line, 7, Math.min(72, line.length()));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private CopybookParser(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            if (line.length() <= 6 || line.charAt(6) == '*' || line.charAt(6) == '/') {
                continue;
            }
            text.append(' ').append(line, 7, Math.min(72, line.length()));
        }
        Deque<Item> stack = new ArrayDeque<>();
        for (String stmt : splitStatements(text.toString())) {
            Item item = parse(stmt);
            if (item == null) {
                continue;
            }
            while (!stack.isEmpty() && stack.peek().level >= item.level && item.level != 88) {
                stack.pop();
            }
            if (!stack.isEmpty()) {
                stack.peek().children.add(item);
            }
            if (item.level != 88) {
                stack.push(item);
            }
            byName.putIfAbsent(item.name, item);
        }
    }

    Item item(String name) {
        Item i = byName.get(name);
        if (i == null) {
            throw new AssertionError("No item " + name + " in copybook");
        }
        return i;
    }

    List<Item> redefinersOf(String name) {
        return byName.values().stream().filter(i -> name.equals(i.redefines)).toList();
    }

    private static List<String> splitStatements(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            if (c == '.' && !quoted && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        return out;
    }

    private static Item parse(String stmt) {
        String[] t = stmt.trim().split("\\s+");
        if (t.length < 2 || !t[0].matches("\\d{1,2}")) {
            return null;
        }
        String pic = null;
        String redefines = null;
        Usage usage = Usage.DISPLAY;
        for (int i = 2; i < t.length; i++) {
            String tok = t[i].toUpperCase();
            switch (tok) {
                case "PIC", "PICTURE" -> pic = t[++i];
                case "REDEFINES" -> redefines = t[++i];
                case "COMP", "COMP-3", "COMP-4", "COMP-5", "BINARY", "PACKED-DECIMAL",
                     "COMPUTATIONAL", "COMPUTATIONAL-3", "COMPUTATIONAL-5" -> usage = Usage.fromCobol(tok);
                default -> {
                }
            }
        }
        Matcher m = VALUE.matcher(stmt);
        String value = m.find() ? (m.group(2) != null ? m.group(2) : m.group(1)) : null;
        return new Item(Integer.parseInt(t[0]), t[1], pic, usage, redefines, value, new ArrayList<>());
    }
}
