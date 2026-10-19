package com.ibm.bankofz.bridge.gen;

import com.ibm.bankofz.bridge.runtime.Ebcdic;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Generates one Java class per copybook record from {@code src/base/cics/copy} (CP037) and
 * {@code src/base/ims/copy} (CP1047, as declared by the IMS DBDs).
 *
 * <p>Usage: {@code Generate <repoRoot> <outputSourceDir>}.
 */
public final class Generate {
    public static final String BASE_PACKAGE = "com.ibm.bankofz.bridge.copybook";

    record Tree(String dir, String pkg, Charset charset, String charsetConst, String indexClass) {
    }

    static final List<Tree> TREES = List.of(
            new Tree("src/base/cics/copy", BASE_PACKAGE + ".cics", Ebcdic.CP037, "CP037", "CicsCopybooks"),
            new Tree("src/base/ims/copy", BASE_PACKAGE + ".ims", Ebcdic.CP1047, "CP1047", "ImsCopybooks"));

    private Generate() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: Generate <repoRoot> <outputSourceDir>");
        }
        Path out = Path.of(args[1]);
        Map<String, String> files = generate(Path.of(args[0]));
        for (Tree t : TREES) {
            Path dir = out.resolve(t.pkg().replace('.', '/'));
            if (Files.isDirectory(dir)) {
                try (Stream<Path> old = Files.list(dir)) {
                    for (Path p : old.filter(f -> f.toString().endsWith(".java")).toList()) {
                        Files.delete(p);
                    }
                }
            }
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = out.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        System.out.println("generated " + files.size() + " files under " + out);
    }

    /** Relative source path -> content, for every generated file. */
    public static Map<String, String> generate(Path repoRoot) throws IOException {
        Map<String, String> files = new TreeMap<>();
        for (Tree t : TREES) {
            List<String[]> records = new ArrayList<>();
            List<String[]> tables = new ArrayList<>();
            List<String[]> skipped = new ArrayList<>();
            List<Path> copybooks;
            try (Stream<Path> s = Files.list(repoRoot.resolve(t.dir()))) {
                copybooks = s.filter(p -> p.toString().endsWith(".cpy")).sorted().toList();
            }
            for (Path cpy : copybooks) {
                String member = cpy.getFileName().toString().replace(".cpy", "");
                String source = t.dir() + "/" + cpy.getFileName();
                Parser.Result result = Parser.parse(Tokenizer.tokenize(cpy), source);
                switch (result) {
                    case Parser.NotData nd -> skipped.add(new String[] {member, nd.reason()});
                    case Parser.Table tb -> {
                        String cls = Names.pascal(member);
                        files.put(path(t, cls), Emitter.emitTable(tb.table(), t.pkg(), cls));
                        tables.add(new String[] {member, cls});
                    }
                    case Parser.Records r -> {
                        List<Item> roots = recordRoots(r.roots(), member);
                        for (Item root : roots) {
                            String cls = roots.size() == 1 ? Names.pascal(member)
                                    : Names.pascal(member) + Names.pascal(root.name);
                            Layout.compute(root, source);
                            String java = new Emitter(root, source, t.pkg(), cls, t.charset(), t.charsetConst()).emit();
                            files.put(path(t, cls), java);
                            records.add(new String[] {roots.size() == 1 ? member : member + "/" + root.name, cls});
                        }
                    }
                }
            }
            files.put(path(t, t.indexClass()), index(t, records, tables, skipped));
        }
        return files;
    }

    /** A copybook of level 03+ items is a fragment the caller copies under its own 01; wrap it. */
    private static List<Item> recordRoots(List<Item> roots, String member) {
        boolean fragment = roots.stream().anyMatch(i -> i.level != 1 && i.level != 77);
        if (!fragment) {
            return roots;
        }
        Item wrapper = new Item();
        wrapper.level = 1;
        wrapper.name = member;
        wrapper.line = roots.get(0).line;
        for (Item i : roots) {
            i.parent = wrapper;
            wrapper.children.add(i);
        }
        return List.of(wrapper);
    }

    private static String path(Tree t, String cls) {
        return t.pkg().replace('.', '/') + "/" + cls + ".java";
    }

    private static String index(Tree t, List<String[]> records, List<String[]> tables, List<String[]> skipped) {
        StringBuilder sb = new StringBuilder(String.format(Emitter.HEADER, t.dir()));
        sb.append("package ").append(t.pkg()).append(";\n\n");
        sb.append("import com.ibm.bankofz.bridge.runtime.Db2Table;\n");
        sb.append("import com.ibm.bankofz.bridge.runtime.RecordLayout;\n\n");
        sb.append("import java.util.Map;\n\n");
        sb.append("/** Every copybook in {@code ").append(t.dir()).append("} and what was generated for it. */\n");
        sb.append("public final class ").append(t.indexClass()).append(" {\n");
        sb.append("    /** Copybook member (or member/01-name) -> record layout. */\n");
        sb.append("    public static final Map<String, RecordLayout> LAYOUTS = Map.ofEntries(");
        for (int i = 0; i < records.size(); i++) {
            sb.append(i == 0 ? "\n            " : ",\n            ").append("Map.entry(")
                    .append(Names.javaString(records.get(i)[0])).append(", ").append(records.get(i)[1])
                    .append(".LAYOUT)");
        }
        sb.append(");\n\n    /** EXEC SQL DECLARE TABLE copybooks. */\n");
        sb.append("    public static final Map<String, Db2Table> TABLES = Map.ofEntries(");
        for (int i = 0; i < tables.size(); i++) {
            sb.append(i == 0 ? "\n            " : ",\n            ").append("Map.entry(")
                    .append(Names.javaString(tables.get(i)[0])).append(", ").append(tables.get(i)[1])
                    .append(".TABLE)");
        }
        sb.append(");\n\n    /** Copybooks with no data layout (procedure code etc.) -> reason. */\n");
        sb.append("    public static final Map<String, String> NOT_DATA = Map.ofEntries(");
        for (int i = 0; i < skipped.size(); i++) {
            sb.append(i == 0 ? "\n            " : ",\n            ").append("Map.entry(")
                    .append(Names.javaString(skipped.get(i)[0])).append(", ")
                    .append(Names.javaString(skipped.get(i)[1])).append(")");
        }
        sb.append(");\n\n    private ").append(t.indexClass()).append("() {\n    }\n}\n");
        return sb.toString();
    }
}
