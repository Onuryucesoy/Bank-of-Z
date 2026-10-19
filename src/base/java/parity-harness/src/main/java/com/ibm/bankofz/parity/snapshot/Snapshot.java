package com.ibm.bankofz.parity.snapshot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Persisted state of a set of Db2 tables or IMS databases, as canonical text: rows sorted by key, values
 * rendered so that equal state gives byte-identical files (diffable with plain {@code diff} too).
 */
public final class Snapshot {
    public static final String HEADER = "#bankz-snapshot v1";
    public static final String NULL = "\\N";

    /** How a column is compared; DECIMAL covers Db2 DECIMAL and IMS packed fields (COMP-3 on disk). */
    public enum ColumnKind { DECIMAL, INTEGER, TEXT, BINARY }

    public record Column(String name, ColumnKind kind) {
    }

    public static final class Table {
        private final String name;
        private final List<Column> columns;
        private final TreeMap<String, List<String>> rows = new TreeMap<>();

        public Table(String name, List<Column> columns) {
            this.name = name;
            this.columns = List.copyOf(columns);
        }

        public String name() { return name; }
        public List<Column> columns() { return columns; }
        public Map<String, List<String>> rows() { return rows; }

        /** Adds a row; non-unique keys get a {@code #n} suffix in arrival order. */
        public void put(String key, List<String> values) {
            if (values.size() != columns.size()) {
                throw new IllegalArgumentException(name + ": row " + key + " has " + values.size()
                        + " values for " + columns.size() + " columns");
            }
            String k = key;
            for (int n = 2; rows.containsKey(k); n++) {
                k = key + "#" + n;
            }
            rows.put(k, java.util.Collections.unmodifiableList(new ArrayList<>(values)));
        }
    }

    private final Map<String, Table> tables = new TreeMap<>();

    public Table table(String name, List<Column> columns) {
        Table t = new Table(name, columns);
        if (tables.putIfAbsent(name, t) != null) {
            throw new IllegalArgumentException("duplicate table " + name);
        }
        return t;
    }

    public Map<String, Table> tables() {
        return tables;
    }

    public void write(Writer w) throws IOException {
        w.write(HEADER);
        w.write('\n');
        for (Table t : tables.values()) {
            List<String> cols = new ArrayList<>();
            for (Column c : t.columns) {
                cols.add(c.name() + ":" + c.kind());
            }
            w.write("@table\t" + escape(t.name) + "\t" + String.join("\t", cols) + "\n");
            for (Map.Entry<String, List<String>> r : t.rows.entrySet()) {
                StringBuilder sb = new StringBuilder("row\t").append(escape(r.getKey()));
                for (String v : r.getValue()) {
                    sb.append('\t').append(v == null ? NULL : escape(v));
                }
                w.write(sb.append('\n').toString());
            }
        }
        w.flush();
    }

    public void write(Path file) throws IOException {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(w);
        }
    }

    public static Snapshot read(Reader in) throws IOException {
        Snapshot s = new Snapshot();
        BufferedReader r = new BufferedReader(in);
        Table current = null;
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            if (f[0].equals("@table")) {
                List<Column> cols = new ArrayList<>();
                for (int i = 2; i < f.length; i++) {
                    int colon = f[i].lastIndexOf(':');
                    cols.add(new Column(f[i].substring(0, colon), ColumnKind.valueOf(f[i].substring(colon + 1))));
                }
                current = s.table(unescape(f[1]), cols);
            } else if (f[0].equals("row") && current != null) {
                List<String> vals = new ArrayList<>();
                for (int i = 2; i < f.length; i++) {
                    vals.add(f[i].equals(NULL) ? null : unescape(f[i]));
                }
                current.rows.put(unescape(f[1]), java.util.Collections.unmodifiableList(vals));
            } else {
                throw new IOException("snapshot line " + n + ": unexpected record " + f[0]);
            }
        }
        return s;
    }

    public static Snapshot read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    static String escape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Convenience for building rows in column order. */
    public static List<String> row(Map<String, String> byColumn, List<Column> columns) {
        List<String> out = new ArrayList<>();
        for (Column c : columns) {
            out.add(byColumn.get(c.name()));
        }
        return out;
    }

    static Map<String, String> asMap(List<Column> columns, List<String> values) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            m.put(columns.get(i).name(), values.get(i));
        }
        return m;
    }
}
