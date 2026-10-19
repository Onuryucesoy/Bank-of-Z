package com.ibm.bankofz.bridge.gen;

import com.ibm.bankofz.bridge.gen.Item.Condition;
import com.ibm.bankofz.bridge.gen.Item.Literal;
import com.ibm.bankofz.bridge.gen.Tokenizer.Token;
import com.ibm.bankofz.bridge.runtime.Db2Table;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/** Parses the DATA DIVISION subset used by copybooks, or an EXEC SQL DECLARE TABLE block. */
final class Parser {
    sealed interface Result permits Records, Table, NotData {
    }

    /** Top-level items: 01/77 records, or level-03+ fragments meant to be copied under a caller's 01. */
    record Records(List<Item> roots) implements Result {
    }

    record Table(Db2Table table) implements Result {
    }

    record NotData(String reason) implements Result {
    }

    private static final Set<String> USAGES = Set.of("DISPLAY", "COMP", "COMPUTATIONAL", "COMP-3",
            "COMPUTATIONAL-3", "PACKED-DECIMAL", "COMP-4", "COMPUTATIONAL-4", "BINARY", "COMP-5",
            "COMPUTATIONAL-5", "POINTER", "INDEX", "COMP-1", "COMP-2", "COMPUTATIONAL-1", "COMPUTATIONAL-2",
            "NATIONAL", "PROCEDURE-POINTER", "FUNCTION-POINTER");

    private List<Token> tokens;
    private final String source;
    private int pos;

    private Parser(List<Token> tokens, String source) {
        this.tokens = tokens;
        this.source = source;
    }

    static Result parse(List<Token> tokens, String source) {
        return new Parser(tokens, source).run();
    }

    private Result run() {
        if (tokens.isEmpty()) {
            return new NotData("empty");
        }
        if (tokens.get(0).is("EXEC") && tokens.size() > 1 && tokens.get(1).is("SQL")) {
            return parseSqlDeclare();
        }
        if (!isLevel(tokens.get(0).text())) {
            return new NotData("procedure-division code, first token '" + tokens.get(0).text() + "'");
        }
        tokens = tokens.stream().filter(t -> !t.is(",")).toList();
        List<Item> roots = new ArrayList<>();
        Deque<Item> stack = new ArrayDeque<>();
        Item last = null;
        while (pos < tokens.size()) {
            Token levelTok = next();
            if (!isLevel(levelTok.text())) {
                if (looksLikeCode(levelTok)) {
                    throw error(levelTok, "procedure-division code mixed with data items");
                }
                throw error(levelTok, "expected a level number");
            }
            int level = Integer.parseInt(levelTok.text());
            if (level == 66) {
                throw error(levelTok, "level 66 RENAMES is not supported");
            }
            if (level == 88) {
                if (last == null) {
                    throw error(levelTok, "88 without a conditional variable");
                }
                last.conditions.add(parseCondition(levelTok));
                continue;
            }
            Item item = parseEntry(level, levelTok.line());
            int effective = level == 77 ? 1 : level;
            while (!stack.isEmpty() && levelOf(stack.peek()) >= effective) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(item);
            } else {
                item.parent = stack.peek();
                stack.peek().children.add(item);
            }
            stack.push(item);
            last = item;
        }
        return new Records(roots);
    }

    private static int levelOf(Item i) {
        return i.level == 77 ? 1 : i.level;
    }

    private Item parseEntry(int level, int line) {
        Item item = new Item();
        item.level = level;
        item.line = line;
        Token t = peek();
        if (!t.is(".") && !isKeyword(t.text())) {
            item.name = next().text().toUpperCase();
        }
        while (!peek().is(".")) {
            Token k = next();
            String kw = k.text().toUpperCase();
            switch (kw) {
                case "REDEFINES" -> item.redefines = next().text().toUpperCase();
                case "PIC", "PICTURE" -> {
                    if (peek().is("IS")) {
                        next();
                    }
                    item.picture = next().text().toUpperCase();
                }
                case "USAGE" -> {
                    if (peek().is("IS")) {
                        next();
                    }
                    item.usage = normalizeUsage(next());
                }
                case "OCCURS" -> parseOccurs(item);
                case "VALUE", "VALUES" -> {
                    skipOptional("IS", "ARE");
                    item.values.add(parseLiteral(next()));
                }
                case "SIGN" -> {
                    skipOptional("IS");
                    boolean leading = next().is("LEADING");
                    boolean separate = false;
                    if (peek().is("SEPARATE")) {
                        next();
                        separate = true;
                        skipOptional("CHARACTER");
                    }
                    item.signClause = leading
                            ? (separate ? ZonedDecimal.Sign.LEADING_SEPARATE : ZonedDecimal.Sign.LEADING)
                            : (separate ? ZonedDecimal.Sign.TRAILING_SEPARATE : ZonedDecimal.Sign.TRAILING);
                }
                case "LEADING", "TRAILING" -> {
                    boolean separate = false;
                    if (peek().is("SEPARATE")) {
                        next();
                        separate = true;
                        skipOptional("CHARACTER");
                    }
                    item.signClause = kw.equals("LEADING")
                            ? (separate ? ZonedDecimal.Sign.LEADING_SEPARATE : ZonedDecimal.Sign.LEADING)
                            : (separate ? ZonedDecimal.Sign.TRAILING_SEPARATE : ZonedDecimal.Sign.TRAILING);
                }
                case "SYNC", "SYNCHRONIZED", "JUST", "JUSTIFIED", "BLANK" ->
                        throw error(k, kw + " is not supported by the bridge generator");
                case "GLOBAL", "EXTERNAL" -> {
                }
                default -> {
                    if (USAGES.contains(kw)) {
                        item.usage = normalizeUsage(k);
                    } else {
                        throw error(k, "unexpected clause '" + k.text() + "'");
                    }
                }
            }
        }
        next();
        return item;
    }

    private void parseOccurs(Item item) {
        int first = Integer.parseInt(next().text());
        item.occursMin = first;
        item.occursMax = first;
        if (peek().is("TO")) {
            next();
            item.occursMax = Integer.parseInt(next().text());
        }
        skipOptional("TIMES");
        if (peek().is("DEPENDING")) {
            next();
            skipOptional("ON");
            item.dependingOn = next().text().toUpperCase();
        }
        while (peek().is("INDEXED") || peek().is("ASCENDING") || peek().is("DESCENDING")) {
            Token k = next();
            if (k.is("INDEXED")) {
                skipOptional("BY");
                next();
            } else {
                skipOptional("KEY");
                skipOptional("IS");
                next();
            }
        }
    }

    private Condition parseCondition(Token levelTok) {
        String name = next().text().toUpperCase();
        Token v = next();
        if (!v.is("VALUE") && !v.is("VALUES")) {
            throw error(v, "expected VALUE");
        }
        skipOptional("IS", "ARE");
        List<Literal[]> ranges = new ArrayList<>();
        while (!peek().is(".")) {
            Literal low = parseLiteral(next());
            Literal high = low;
            if (peek().is("THRU") || peek().is("THROUGH")) {
                next();
                high = parseLiteral(next());
            }
            ranges.add(new Literal[] {low, high});
        }
        next();
        return new Condition(name, ranges, levelTok.line());
    }

    private Literal parseLiteral(Token t) {
        String s = t.text();
        String u = s.toUpperCase();
        if (u.equals("ALL")) {
            throw error(t, "ALL literals are not supported");
        }
        if (s.startsWith("'") || s.startsWith("\"")) {
            char q = s.charAt(0);
            String body = s.substring(1, s.length() - 1).replace(String.valueOf(q) + q, String.valueOf(q));
            return new Literal(Literal.Type.STRING, body);
        }
        if (u.startsWith("X'") || u.startsWith("X\"")) {
            return new Literal(Literal.Type.HEX, s.substring(2, s.length() - 1));
        }
        return switch (u) {
            case "SPACE", "SPACES" -> new Literal(Literal.Type.SPACE, " ");
            case "ZERO", "ZEROS", "ZEROES" -> new Literal(Literal.Type.ZERO, "0");
            case "LOW-VALUE", "LOW-VALUES" -> new Literal(Literal.Type.LOW_VALUE, "");
            case "HIGH-VALUE", "HIGH-VALUES" -> new Literal(Literal.Type.HIGH_VALUE, "");
            default -> {
                if (!u.matches("[+-]?\\d*\\.?\\d+")) {
                    throw error(t, "unsupported literal " + s);
                }
                yield new Literal(Literal.Type.NUMBER, s);
            }
        };
    }

    private Result parseSqlDeclare() {
        pos = 2;
        expect("DECLARE");
        String table = next().text().toUpperCase();
        expect("TABLE");
        StringBuilder body = new StringBuilder();
        while (!peek().is("END-EXEC")) {
            Token t = next();
            body.append(t.is(",") ? ", " : t.text() + " ");
        }
        String text = body.toString().trim();
        if (!text.startsWith("(") || !text.endsWith(")")) {
            throw new IllegalStateException(source + ": malformed DECLARE TABLE");
        }
        text = text.substring(1, text.length() - 1);
        List<Db2Table.Column> cols = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            char c = i < text.length() ? text.charAt(i) : ',';
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                String def = text.substring(start, i).trim();
                if (!def.isEmpty()) {
                    cols.add(column(def));
                }
                start = i + 1;
            }
        }
        return new Table(new Db2Table(table, source, cols));
    }

    private Db2Table.Column column(String def) {
        String[] words = def.replaceAll("\\s*\\(\\s*", "(").replaceAll("\\s*\\)", ")")
                .replaceAll("\\s*,\\s*", ",").split("\\s+");
        String name = words[0].toUpperCase();
        String type = words[1].toUpperCase();
        int length = 0;
        int scale = 0;
        int p = type.indexOf('(');
        if (p > 0) {
            String[] args = type.substring(p + 1, type.length() - 1).split(",");
            length = Integer.parseInt(args[0]);
            scale = args.length > 1 ? Integer.parseInt(args[1]) : 0;
            type = type.substring(0, p);
        }
        boolean nullable = !def.toUpperCase().contains("NOT NULL");
        return new Db2Table.Column(name, type, length, scale, nullable);
    }

    private static String normalizeUsage(Token t) {
        String u = t.text().toUpperCase().replace("COMPUTATIONAL", "COMP");
        return switch (u) {
            case "COMP", "COMP-4", "BINARY" -> "BINARY";
            case "COMP-3", "PACKED-DECIMAL" -> "PACKED";
            case "COMP-5" -> "COMP-5";
            default -> u;
        };
    }

    private static boolean isLevel(String s) {
        return s.matches("\\d{1,2}") && (Integer.parseInt(s) <= 49 || s.equals("66") || s.equals("77")
                || s.equals("88")) && Integer.parseInt(s) > 0;
    }

    private static boolean looksLikeCode(Token t) {
        return Set.of("MOVE", "EVALUATE", "IF", "PERFORM", "EXEC", "CALL", "COMPUTE").contains(t.text().toUpperCase());
    }

    private static boolean isKeyword(String s) {
        String u = s.toUpperCase();
        return USAGES.contains(u) || Set.of("PIC", "PICTURE", "REDEFINES", "OCCURS", "VALUE", "USAGE", "SIGN")
                .contains(u);
    }

    private void skipOptional(String... words) {
        for (String w : words) {
            if (pos < tokens.size() && peek().is(w)) {
                next();
            }
        }
    }

    private void expect(String word) {
        Token t = next();
        if (!t.is(word)) {
            throw error(t, "expected " + word);
        }
    }

    private Token peek() {
        if (pos >= tokens.size()) {
            throw new IllegalStateException(source + ": unexpected end of copybook");
        }
        return tokens.get(pos);
    }

    private Token next() {
        Token t = peek();
        pos++;
        return t;
    }

    private IllegalStateException error(Token t, String msg) {
        return new IllegalStateException(source + ":" + t.line() + ": " + msg);
    }
}
