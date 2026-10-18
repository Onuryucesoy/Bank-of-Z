package com.ibm.bankofz.core.copybook;

import com.ibm.bankofz.core.codec.Binary;
import com.ibm.bankofz.core.codec.PackedDecimal;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed PICTURE clause. Numeric pictures (9, S, V, P) are decoded to
 * digits/scale; anything else (X, A, edited masks such as Z,ZZ9.99) is treated
 * as alphanumeric with its display length.
 */
public record Pic(String clause, boolean numeric, boolean signed, int digits, int scale, int displayLength) {

    private static final Pattern REPEAT = Pattern.compile("(.)\\((\\d+)\\)");

    public static Pic parse(String clause) {
        String expanded = expand(clause.toUpperCase(Locale.ROOT));
        boolean numeric = expanded.matches("S?[9P]*V?[9P]*") && expanded.indexOf('9') >= 0;
        if (numeric) {
            boolean signed = expanded.startsWith("S");
            String body = signed ? expanded.substring(1) : expanded;
            int v = body.indexOf('V');
            int intDigits = (int) (v < 0 ? body : body.substring(0, v)).chars().filter(c -> c == '9').count();
            int scale = v < 0 ? 0 : (int) body.substring(v + 1).chars().filter(c -> c == '9').count();
            int digits = intDigits + scale;
            return new Pic(clause, true, signed, digits, scale, digits);
        }
        int len = (int) expanded.chars().filter(c -> c != 'V' && c != 'S').count();
        return new Pic(clause, false, false, 0, 0, len);
    }

    public int storageLength(Usage usage, boolean signSeparate) {
        if (!numeric) {
            return displayLength;
        }
        return switch (usage) {
            case DISPLAY -> digits + (signSeparate ? 1 : 0);
            case PACKED -> PackedDecimal.byteLength(digits);
            case BINARY, NATIVE_BINARY -> Binary.byteLength(digits);
            case POINTER -> 8;
        };
    }

    private static String expand(String clause) {
        Matcher m = REPEAT.matcher(clause);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, m.group(1).repeat(Integer.parseInt(m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
