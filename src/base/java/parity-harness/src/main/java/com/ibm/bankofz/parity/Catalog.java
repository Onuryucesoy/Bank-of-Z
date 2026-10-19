package com.ibm.bankofz.parity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The capture catalog ({@code catalog.tsv}): which program is captured how, and with which layouts. */
public final class Catalog {
    public record Entry(String system, String transaction, String program, String iface,
                        String requestLayout, String responseLayout, java.util.Set<String> volatileFields) {
    }

    public static final List<Entry> ENTRIES = load();

    private Catalog() {
    }

    public static Optional<Entry> find(String system, String program) {
        return ENTRIES.stream().filter(e -> e.system().equals(system) && e.program().equals(program)).findFirst();
    }

    private static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try (InputStream in = Catalog.class.getResourceAsStream("catalog.tsv");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                out.add(new Entry(f[0], f[1], f[2], f[3], f[4], f[5],
                        f[6].equals("-") ? java.util.Set.of() : java.util.Set.of(f[6].split(","))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(out);
    }
}
