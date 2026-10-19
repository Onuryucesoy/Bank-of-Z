package com.ibm.bankofz.parity;

import com.ibm.bankofz.parity.compare.Divergence;
import com.ibm.bankofz.parity.compare.Divergence.Kind;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Record-by-record diff of two print reports (legacy and candidate SYSPRINT, ASA control character
 * included). A line whose numbers differ is a NUMERIC divergence (blocker); other differences are TEXT.
 */
public final class ReportDiff {
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?");

    private ReportDiff() {
    }

    public static ParityReport diff(List<String> legacy, List<String> candidate) {
        ParityReport r = new ParityReport("report parity: legacy vs candidate");
        int n = Math.max(legacy.size(), candidate.size());
        for (int i = 0; i < n; i++) {
            r.countCompared();
            String a = i < legacy.size() ? legacy.get(i) : null;
            String b = i < candidate.size() ? candidate.get(i) : null;
            if (a != null && a.equals(b)) {
                continue;
            }
            String scope = "record " + (i + 1);
            if (a == null || b == null) {
                r.add(new Divergence(Kind.MISSING, scope, "<record>", String.valueOf(a), String.valueOf(b)));
            } else {
                r.add(new Divergence(numbers(a).equals(numbers(b)) ? Kind.TEXT : Kind.NUMERIC, scope, "<record>", a, b));
            }
        }
        return r;
    }

    static List<String> numbers(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** Reads {@code //DD:name} through JZOS on z/OS (EBCDIC records), otherwise a UTF-8 file. */
    public static List<String> lines(String name) throws IOException {
        if (name.startsWith("//DD:")) {
            Charset ebcdic = Charset.forName("IBM1047");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new com.ibm.jzos.ZFile(name, "rt").getInputStream(), ebcdic))) {
                return r.lines().toList();
            }
        }
        return Files.readAllLines(Path.of(name), StandardCharsets.UTF_8);
    }
}
