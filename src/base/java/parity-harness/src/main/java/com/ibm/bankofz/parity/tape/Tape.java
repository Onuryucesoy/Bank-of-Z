package com.ibm.bankofz.parity.tape;

import com.ibm.bankofz.bridge.runtime.Hex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Capture tape: a UTF-8 text file, one exchange per line, tab-separated, payloads in hex so the file is
 * byte-exact regardless of the code page of the system that moves it (zFS, USS, Git, a PC).
 */
public final class Tape {
    public static final String HEADER = "#bankz-tape v1\tseq\tsystem\ttransaction\tprogram\trequestLayout"
            + "\tresponseLayout\toutcome\trequest\tresponse";

    private Tape() {
    }

    public static String line(Exchange e) {
        return String.join("\t", Long.toString(e.seq()), e.system(), e.transaction(), e.program(),
                e.requestLayout(), e.responseLayout(), e.outcome(), Hex.of(e.request()), Hex.of(e.response()));
    }

    public static Exchange parse(String line, int lineNo) {
        String[] f = line.split("\t", -1);
        if (f.length != 9) {
            throw new IllegalArgumentException("tape line " + lineNo + ": expected 9 fields, found " + f.length);
        }
        try {
            return new Exchange(Long.parseLong(f[0]), f[1], f[2], f[3], f[4], f[5], f[6],
                    Hex.parse(f[7]), Hex.parse(f[8]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("tape line " + lineNo + ": " + e.getMessage(), e);
        }
    }

    public static List<Exchange> read(Reader in) throws IOException {
        List<Exchange> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(in);
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) {
            n++;
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            out.add(parse(line, n));
        }
        return out;
    }

    public static List<Exchange> read(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(r);
        }
    }

    public static void write(Writer out, List<Exchange> exchanges) throws IOException {
        out.write(HEADER);
        out.write('\n');
        for (Exchange e : exchanges) {
            out.write(line(e));
            out.write('\n');
        }
        out.flush();
    }

    public static void write(Path file, List<Exchange> exchanges) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            write(w, exchanges);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
