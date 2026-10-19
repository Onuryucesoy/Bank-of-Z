package com.ibm.bankofz.bridge.gen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Fixed-format COBOL source to tokens. A separator period is returned as the token ".". */
final class Tokenizer {
    record Token(String text, int line) {
        boolean is(String s) {
            return text.equalsIgnoreCase(s);
        }
    }

    private Tokenizer() {
    }

    static List<Token> tokenize(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.ISO_8859_1);
        List<Token> out = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            String raw = lines.get(n);
            if (raw.length() < 7) {
                continue;
            }
            char indicator = raw.charAt(6);
            if (indicator == '*' || indicator == '/') {
                continue;
            }
            if (indicator == '-') {
                throw new IllegalStateException(file + ":" + (n + 1) + ": continuation lines are not supported");
            }
            String area = raw.substring(7, Math.min(72, raw.length()));
            int inline = indexOutsideQuotes(area, "*>");
            if (inline >= 0) {
                area = area.substring(0, inline);
            }
            scan(area, n + 1, out, file);
        }
        return out;
    }

    private static void scan(String s, int line, List<Token> out, Path file) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            boolean hexOrNational = (c == 'X' || c == 'x') && i + 1 < s.length()
                    && (s.charAt(i + 1) == '\'' || s.charAt(i + 1) == '"');
            if (c == '\'' || c == '"' || hexOrNational) {
                int q = hexOrNational ? i + 1 : i;
                char quote = s.charAt(q);
                int j = q + 1;
                while (true) {
                    if (j >= s.length()) {
                        throw new IllegalStateException(file + ":" + line + ": unterminated literal");
                    }
                    if (s.charAt(j) == quote) {
                        if (j + 1 < s.length() && s.charAt(j + 1) == quote) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                out.add(new Token(s.substring(start, j + 1), line));
                i = j + 1;
                if (i < s.length() && s.charAt(i) == '.' && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)))) {
                    out.add(new Token(".", line));
                    i++;
                }
                continue;
            }
            while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            String word = s.substring(start, i);
            boolean period = word.endsWith(".");
            if (period) {
                word = word.substring(0, word.length() - 1);
            }
            boolean comma = word.endsWith(",") || word.endsWith(";");
            if (comma) {
                word = word.substring(0, word.length() - 1);
            }
            if (!word.isEmpty()) {
                out.add(new Token(word, line));
            }
            if (comma) {
                out.add(new Token(",", line));
            }
            if (period) {
                out.add(new Token(".", line));
            }
        }
    }

    private static int indexOutsideQuotes(String s, String needle) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (s.startsWith(needle, i)) {
                return i;
            }
        }
        return -1;
    }
}
