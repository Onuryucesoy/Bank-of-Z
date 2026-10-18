package com.ibm.bankofz.core.copybook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal fixed-format COBOL copybook parser covering the constructs used in
 * the Bank of Z copybooks: levels 01-49, PIC, USAGE (DISPLAY, COMP, COMP-3,
 * COMP-5, BINARY, POINTER), OCCURS [n TO] m [DEPENDING ON], REDEFINES,
 * SIGN SEPARATE, VALUE (ignored) and 88-levels (ignored). EXEC SQL blocks are
 * skipped. Copybooks that start below level 01 are wrapped in a synthetic 01
 * named after the copybook.
 */
public final class CopybookParser {

    private static final Set<String> CLAUSE_WORDS = Set.of(
            "PIC", "PICTURE", "USAGE", "COMP", "COMP-3", "COMP-4", "COMP-5", "COMPUTATIONAL",
            "COMPUTATIONAL-3", "COMPUTATIONAL-4", "COMPUTATIONAL-5", "BINARY", "PACKED-DECIMAL",
            "DISPLAY", "POINTER", "OCCURS", "REDEFINES", "VALUE", "VALUES", "SIGN", "JUSTIFIED",
            "JUST", "SYNC", "SYNCHRONIZED", "BLANK", "GLOBAL", "EXTERNAL");

    private CopybookParser() {
    }

    public static Copybook parse(Path file) throws IOException {
        String name = file.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        return parse(name, Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    public static Copybook parse(String name, List<String> lines) {
        List<List<String>> statements = statements(lines);
        Node root = null;
        List<Node> records = new ArrayList<>();
        Deque<Node> stack = new ArrayDeque<>();
        for (List<String> tokens : statements) {
            int level = Integer.parseInt(tokens.get(0));
            if (level == 88 || level == 66) {
                continue;
            }
            Node node = node(level, tokens);
            if (level == 1 || level == 77) {
                records.add(node);
                stack.clear();
                stack.push(node);
                continue;
            }
            if (stack.isEmpty()) {
                root = new Node(1, name);
                records.add(root);
                stack.push(root);
            }
            while (stack.peek().level >= level) {
                stack.pop();
                if (stack.isEmpty()) {
                    throw new IllegalArgumentException(name + ": level " + level + " has no parent");
                }
            }
            stack.peek().children.add(node);
            stack.push(node);
        }
        List<Field> out = new ArrayList<>();
        int recordOffset = 0;
        Node previous = null;
        for (Node r : records) {
            int base = r.redefines != null && previous != null ? 0 : recordOffset;
            Field f = layout(r, 0, false);
            out.add(f);
            if (r.redefines == null) {
                previous = r;
            }
            recordOffset = base;
        }
        return new Copybook(name, out);
    }

    private static Node node(int level, List<String> t) {
        int i = 1;
        String name = "FILLER";
        if (t.size() > 1 && !CLAUSE_WORDS.contains(t.get(1).toUpperCase(Locale.ROOT))) {
            name = t.get(1).toUpperCase(Locale.ROOT);
            i = 2;
        }
        Node n = new Node(level, name);
        while (i < t.size()) {
            String w = t.get(i).toUpperCase(Locale.ROOT);
            switch (w) {
                case "PIC", "PICTURE" -> {
                    i++;
                    if (t.get(i).equalsIgnoreCase("IS")) {
                        i++;
                    }
                    n.pic = Pic.parse(t.get(i));
                }
                case "USAGE", "IS" -> { }
                case "COMP", "COMP-4", "COMPUTATIONAL", "COMPUTATIONAL-4", "BINARY" -> n.usage = Usage.BINARY;
                case "COMP-5", "COMPUTATIONAL-5" -> n.usage = Usage.NATIVE_BINARY;
                case "COMP-3", "COMPUTATIONAL-3", "PACKED-DECIMAL" -> n.usage = Usage.PACKED;
                case "POINTER" -> n.usage = Usage.POINTER;
                case "DISPLAY" -> n.usage = Usage.DISPLAY;
                case "REDEFINES" -> n.redefines = t.get(++i).toUpperCase(Locale.ROOT);
                case "SEPARATE" -> n.signSeparate = true;
                case "LEADING" -> n.signLeading = true;
                case "OCCURS" -> {
                    int first = Integer.parseInt(t.get(++i));
                    n.occurs = first;
                    if (i + 2 < t.size() && t.get(i + 1).equalsIgnoreCase("TO")) {
                        n.occurs = Integer.parseInt(t.get(i + 2));
                        i += 2;
                    }
                }
                case "DEPENDING" -> {
                    i++;
                    if (t.get(i).equalsIgnoreCase("ON")) {
                        i++;
                    }
                    n.dependingOn = t.get(i).toUpperCase(Locale.ROOT);
                }
                case "VALUE", "VALUES" -> i = t.size();
                default -> { }
            }
            i++;
        }
        return n;
    }

    /** Lays out {@code n} at {@code offset}; returns the immutable field tree. */
    private static Field layout(Node n, int offset, boolean underRedefines) {
        boolean inRedefines = underRedefines || n.redefines != null;
        if (n.children.isEmpty()) {
            int len = n.usage == Usage.POINTER ? 8 : n.pic.storageLength(n.usage, n.signSeparate);
            return new Field(n.level, n.name, n.usage == Usage.POINTER ? null : n.pic, n.usage, n.signSeparate, n.signLeading,
                    offset, len, n.occurs, n.dependingOn, n.redefines, inRedefines, List.of());
        }
        List<Field> kids = new ArrayList<>();
        int cursor = offset;
        int end = offset;
        Field last = null;
        for (Node c : n.children) {
            int at = cursor;
            if (c.redefines != null) {
                Field target = find(kids, c.redefines);
                if (target == null) {
                    throw new IllegalArgumentException(c.name + " REDEFINES unknown sibling " + c.redefines);
                }
                at = target.offset();
            }
            Field f = layout(c, at, inRedefines);
            kids.add(f);
            int size = f.length() * f.occursMax();
            if (c.redefines == null) {
                cursor = at + size;
                last = f;
            }
            end = Math.max(end, at + size);
        }
        if (last == null && !kids.isEmpty()) {
            last = kids.get(0);
        }
        return new Field(n.level, n.name, null, Usage.DISPLAY, false, false, offset, end - offset, n.occurs,
                n.dependingOn, n.redefines, inRedefines, List.copyOf(kids));
    }

    private static Field find(List<Field> siblings, String name) {
        for (int i = siblings.size() - 1; i >= 0; i--) {
            if (siblings.get(i).name().equals(name)) {
                return siblings.get(i);
            }
        }
        return null;
    }

    /** Splits the source area (cols 8-72) into period-terminated, tokenized statements. */
    static List<List<String>> statements(List<String> lines) {
        StringBuilder src = new StringBuilder();
        boolean inSql = false;
        for (String raw : lines) {
            if (raw.length() < 7) {
                continue;
            }
            char indicator = raw.charAt(6);
            if (indicator == '*' || indicator == '/') {
                continue;
            }
            String area = raw.substring(7, Math.min(raw.length(), 72));
            int inline = area.indexOf("*>");
            if (inline >= 0) {
                area = area.substring(0, inline);
            }
            String upper = area.toUpperCase(Locale.ROOT);
            if (upper.contains("EXEC SQL")) {
                inSql = true;
            }
            if (inSql) {
                if (upper.contains("END-EXEC")) {
                    inSql = false;
                }
                continue;
            }
            src.append(area).append(' ');
        }
        List<List<String>> out = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        char quote = 0;
        String s = src.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                tok.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                tok.append(c);
            } else if (Character.isWhitespace(c) || c == ',') {
                flush(tok, current);
            } else if (c == '.' && (i + 1 >= s.length() || Character.isWhitespace(s.charAt(i + 1)))) {
                flush(tok, current);
                if (!current.isEmpty()) {
                    out.add(current);
                }
                current = new ArrayList<>();
            } else {
                tok.append(c);
            }
        }
        flush(tok, current);
        if (!current.isEmpty()) {
            out.add(current);
        }
        out.removeIf(st -> !st.get(0).matches("\\d{1,2}"));
        return out;
    }

    private static void flush(StringBuilder tok, List<String> current) {
        if (!tok.isEmpty()) {
            current.add(tok.toString());
            tok.setLength(0);
        }
    }

    private static final class Node {
        final int level;
        final String name;
        Pic pic;
        Usage usage = Usage.DISPLAY;
        boolean signSeparate;
        boolean signLeading;
        int occurs = 1;
        String dependingOn;
        String redefines;
        final List<Node> children = new ArrayList<>();

        Node(int level, String name) {
            this.level = level;
            this.name = name;
        }
    }
}
