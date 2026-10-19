package com.ibm.bankofz.parity.snapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the segment and field layout out of a DBDGEN assembler source in {@code src/base/ims/DBD}, so the
 * IMS snapshot decodes segments with the same definitions IMS uses (no second copy of the layout).
 */
public record Dbd(String name, String encoding, List<Segment> segments) {

    public record Field(String name, int start, int bytes, String datatype, boolean seq, boolean unique) {
        /** 0-based offset in the segment. */
        public int offset() {
            return start - 1;
        }
    }

    public record Segment(String name, String parent, int bytes, List<Field> fields) {
        public Field sequenceField() {
            return fields.stream().filter(Field::seq).findFirst().orElse(null);
        }
    }

    private static final Pattern NUM = Pattern.compile("\\(?(\\d+)");

    public static Dbd parse(Path asm) throws IOException {
        return parse(Files.readAllLines(asm, StandardCharsets.UTF_8));
    }

    public static Dbd parse(List<String> lines) {
        String name = null;
        String encoding = "Cp1047";
        List<Segment> segments = new ArrayList<>();
        String segName = null;
        String segParent = null;
        int segBytes = 0;
        List<Field> fields = new ArrayList<>();
        for (String stmt : statements(lines)) {
            String[] opAndOperands = stmt.trim().split("\\s+", 2);
            String op = opAndOperands[0];
            Map<String, String> kv = operands(opAndOperands.length > 1 ? opAndOperands[1] : "");
            switch (op) {
                case "DBD" -> {
                    name = kv.get("NAME");
                    encoding = kv.getOrDefault("ENCODING", encoding);
                }
                case "SEGM" -> {
                    if (segName != null) {
                        segments.add(new Segment(segName, segParent, segBytes, List.copyOf(fields)));
                    }
                    segName = kv.get("NAME");
                    segParent = kv.getOrDefault("PARENT", "0");
                    segBytes = number(kv.get("BYTES"));
                    fields = new ArrayList<>();
                }
                case "FIELD" -> {
                    String n = kv.get("NAME");
                    boolean seq = false;
                    boolean unique = false;
                    if (n.startsWith("(")) {
                        String[] p = n.substring(1, n.length() - 1).split(",");
                        n = p[0];
                        seq = p.length > 1 && p[1].equals("SEQ");
                        unique = p.length > 2 && p[2].equals("U");
                    }
                    fields.add(new Field(n, number(kv.get("START")), number(kv.get("BYTES")),
                            kv.getOrDefault("DATATYPE", "CHAR"), seq, unique));
                }
                default -> {
                }
            }
        }
        if (segName != null) {
            segments.add(new Segment(segName, segParent, segBytes, List.copyOf(fields)));
        }
        if (name == null) {
            throw new IllegalArgumentException("no DBD statement");
        }
        return new Dbd(name, encoding, List.copyOf(segments));
    }

    public Segment segment(String segName) {
        return segments.stream().filter(s -> s.name().equals(segName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(name + " has no segment " + segName));
    }

    /** Joins assembler continuation lines (non-blank column 72, continued from column 16). */
    static List<String> statements(List<String> lines) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : lines) {
            if (raw.startsWith("*") || raw.isBlank() && cur == null) {
                continue;
            }
            String body = raw.length() > 71 ? raw.substring(0, 71) : raw;
            boolean continued = raw.length() > 71 && raw.charAt(71) != ' ';
            if (cur == null) {
                cur = new StringBuilder(body.stripTrailing());
            } else {
                cur.append(body.length() > 15 ? body.substring(15).strip() : body.strip());
            }
            if (!continued) {
                out.add(cur.toString());
                cur = null;
            }
        }
        return out;
    }

    /** Splits {@code A=1,B=(X,Y),C='a,b'} on top-level commas; stops at the first blank (remarks). */
    static Map<String, String> operands(String s) {
        Map<String, String> out = new LinkedHashMap<>();
        int depth = 0;
        boolean quote = false;
        StringBuilder tok = new StringBuilder();
        List<String> toks = new ArrayList<>();
        for (char c : s.toCharArray()) {
            if (c == '\'') {
                quote = !quote;
            } else if (!quote && c == '(') {
                depth++;
            } else if (!quote && c == ')') {
                depth--;
            } else if (!quote && depth == 0 && c == ',') {
                toks.add(tok.toString());
                tok.setLength(0);
                continue;
            } else if (!quote && depth == 0 && c == ' ') {
                break;
            }
            tok.append(c);
        }
        toks.add(tok.toString());
        for (String t : toks) {
            int eq = t.indexOf('=');
            if (eq > 0) {
                out.put(t.substring(0, eq), t.substring(eq + 1));
            }
        }
        return out;
    }

    private static int number(String v) {
        Matcher m = NUM.matcher(v == null ? "" : v);
        if (!m.lookingAt()) {
            throw new IllegalArgumentException("expected a number, got " + v);
        }
        return Integer.parseInt(m.group(1));
    }
}
