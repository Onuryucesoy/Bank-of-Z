package com.ibm.bankofz.bridge.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Numeric-edited PICTURE support for the symbols used by the Bank of Z copybooks and reports:
 * 9, Z, '.', ',', B, 0, '/', '-', '+', CR, DB and '$' (fixed or floating). {@code picture} must be expanded
 * (no repeat counts), e.g. {@code ZZZZZZZZZZZZZ.99}.
 */
public final class Edited {
    private Edited() {
    }

    public static int digits(String picture) {
        int n = 0;
        boolean seenFloat = false;
        String pic = stripCrDb(picture);
        for (int i = 0; i < pic.length(); i++) {
            char c = pic.charAt(i);
            if (c == '9' || c == 'Z' || c == '*') {
                n++;
            } else if (c == '-' || c == '+' || c == '$') {
                if (seenFloat && isFloating(pic, c)) {
                    n++;
                }
                seenFloat = true;
            }
        }
        return n;
    }

    public static int scale(String picture) {
        int dot = picture.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        int n = 0;
        for (int i = dot + 1; i < picture.length(); i++) {
            char c = picture.charAt(i);
            if (c == '9' || c == 'Z' || c == '*') {
                n++;
            }
        }
        return n;
    }

    /** Formats like a COBOL MOVE of {@code value} to the edited field (truncating, never rounding). */
    public static String format(BigDecimal value, String picture) {
        int scale = scale(picture);
        int digits = digits(picture);
        BigDecimal fitted = Assign.fit(value, digits, scale, true, false);
        boolean negative = fitted.signum() < 0;
        String num = fitted.abs().setScale(scale, RoundingMode.UNNECESSARY).unscaledValue().toString();
        StringBuilder digitsLeft = new StringBuilder(num);
        while (digitsLeft.length() < digits) {
            digitsLeft.insert(0, '0');
        }
        String suffix = "";
        String pic = picture;
        if (pic.endsWith("CR") || pic.endsWith("DB")) {
            suffix = negative ? pic.substring(pic.length() - 2) : "  ";
            pic = pic.substring(0, pic.length() - 2);
        }
        char floatChar = 0;
        for (char c : new char[] {'$', '+', '-'}) {
            if (isFloating(pic, c)) {
                floatChar = c;
            }
        }
        StringBuilder out = new StringBuilder();
        int di = 0;
        boolean significant = false;
        boolean floatPlaced = false;
        boolean firstFloat = true;
        int dot = pic.indexOf('.');
        for (int i = 0; i < pic.length(); i++) {
            char c = pic.charAt(i);
            boolean afterDot = dot >= 0 && i > dot;
            if (c == floatChar && floatChar != 0) {
                if (firstFloat) {
                    firstFloat = false;
                    out.append(' ');
                    continue;
                }
                char d = digitsLeft.charAt(di++);
                if (!significant && d == '0' && !afterDot) {
                    out.append(' ');
                } else {
                    if (!significant) {
                        placeFloat(out, floatChar, negative);
                        floatPlaced = true;
                    }
                    significant = true;
                    out.append(d);
                }
                continue;
            }
            switch (c) {
                case '9' -> {
                    if (floatChar != 0 && !floatPlaced) {
                        placeFloat(out, floatChar, negative);
                        floatPlaced = true;
                    }
                    significant = true;
                    out.append(digitsLeft.charAt(di++));
                }
                case 'Z', '*' -> {
                    char d = digitsLeft.charAt(di++);
                    if (!significant && d == '0' && !(afterDot && fitted.signum() != 0)) {
                        out.append(c == '*' ? '*' : ' ');
                    } else {
                        significant = true;
                        out.append(d);
                    }
                }
                case '.' -> {
                    if (floatChar != 0 && !floatPlaced) {
                        placeFloat(out, floatChar, negative);
                        floatPlaced = true;
                    }
                    out.append(significant || fitted.signum() != 0 || pic.indexOf('9') >= 0 ? '.' : ' ');
                    significant = significant || fitted.signum() != 0;
                }
                case ',', 'B', '0', '/' -> {
                    char ins = c == 'B' ? ' ' : c;
                    out.append(significant ? ins : (c == '0' || c == '/' ? ins : ' '));
                }
                case '+' -> out.append(negative ? '-' : '+');
                case '-' -> out.append(negative ? '-' : ' ');
                case '$' -> out.append('$');
                default -> throw new IllegalArgumentException("unsupported edit symbol '" + c + "' in " + picture);
            }
        }
        if (fitted.signum() == 0 && pic.indexOf('9') < 0 && out.toString().trim().equals(".")) {
            return " ".repeat(out.length()) + suffix.replace("CR", "  ").replace("DB", "  ");
        }
        return out + suffix;
    }

    /** De-edits a numeric-edited value (COBOL MOVE from numeric-edited to numeric). */
    public static BigDecimal parse(String text) {
        String t = text.trim();
        boolean negative = t.endsWith("CR") || t.endsWith("DB") || t.contains("-");
        StringBuilder digits = new StringBuilder();
        for (char c : t.toCharArray()) {
            if (Character.isDigit(c) || c == '.') {
                digits.append(c);
            }
        }
        if (digits.isEmpty() || digits.toString().equals(".")) {
            return BigDecimal.ZERO;
        }
        BigDecimal v = new BigDecimal(digits.toString());
        return negative ? v.negate() : v;
    }

    private static void placeFloat(StringBuilder out, char floatChar, boolean negative) {
        char sym = switch (floatChar) {
            case '$' -> '$';
            case '+' -> negative ? '-' : '+';
            default -> negative ? '-' : ' ';
        };
        out.setCharAt(out.length() - 1, sym);
    }

    private static boolean isFloating(String pic, char c) {
        int first = pic.indexOf(c);
        return first >= 0 && first + 1 < pic.length() && pic.charAt(first + 1) == c;
    }

    private static String stripCrDb(String pic) {
        return pic.endsWith("CR") || pic.endsWith("DB") ? pic.substring(0, pic.length() - 2) : pic;
    }
}
