package com.bankofz.migration.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed COBOL {@code PICTURE} string. Supports the symbols used by the Bank of Z
 * copybooks: {@code X}, {@code A}, {@code 9}, {@code S} and {@code V}, each with an
 * optional repeat count, e.g. {@code X(50)}, {@code 9(4)V99}, {@code S9(13)V9(2)}.
 *
 * @param text          normalised picture string
 * @param alphanumeric  {@code true} for {@code X}/{@code A} pictures
 * @param signed        leading {@code S}
 * @param integerDigits digits before {@code V} (character count for alphanumerics)
 * @param scale         digits after {@code V}; this is the {@link BigDecimal#scale()}
 */
public record PicClause(String text, boolean alphanumeric, boolean signed, int integerDigits, int scale) {

    private static final Pattern SYMBOL = Pattern.compile("([SXAV9])(?:\\((\\d+)\\))?");
    private static final Map<String, PicClause> CACHE = new ConcurrentHashMap<>();

    public PicClause {
        if (integerDigits < 0 || scale < 0 || integerDigits + scale == 0) {
            throw new IllegalArgumentException("Empty PIC: " + text);
        }
    }

    public static PicClause parse(String pic) {
        return CACHE.computeIfAbsent(pic.trim().toUpperCase(Locale.ROOT), PicClause::doParse);
    }

    private static PicClause doParse(String p) {
        Matcher m = SYMBOL.matcher(p);
        int pos = 0;
        boolean signed = false;
        boolean alpha = false;
        boolean afterV = false;
        int intDigits = 0;
        int scale = 0;
        int chars = 0;
        while (pos < p.length()) {
            if (!m.find(pos) || m.start() != pos) {
                throw new IllegalArgumentException("Unsupported PIC symbol in '" + p + "' at " + pos);
            }
            int n = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            switch (m.group(1).charAt(0)) {
                case 'S' -> {
                    if (pos != 0) {
                        throw new IllegalArgumentException("S must lead the PIC: " + p);
                    }
                    signed = true;
                }
                case 'V' -> {
                    if (afterV) {
                        throw new IllegalArgumentException("Duplicate V in PIC: " + p);
                    }
                    afterV = true;
                }
                case '9' -> {
                    if (afterV) {
                        scale += n;
                    } else {
                        intDigits += n;
                    }
                }
                default -> {
                    alpha = true;
                    chars += n;
                }
            }
            pos = m.end();
        }
        if (alpha) {
            if (signed || afterV || intDigits > 0) {
                throw new IllegalArgumentException("Mixed alphanumeric/numeric PIC: " + p);
            }
            return new PicClause(p, true, false, chars, 0);
        }
        return new PicClause(p, false, signed, intDigits, scale);
    }

    /** Total digits (numeric) or characters (alphanumeric). */
    public int length() {
        return integerDigits + scale;
    }

    /** Bytes the item occupies in the record for the given usage. */
    public int storageBytes(Usage usage) {
        if (alphanumeric || usage == Usage.DISPLAY) {
            return length();
        }
        return switch (usage) {
            case COMP_3 -> length() / 2 + 1;
            case COMP, COMP_5 -> length() <= 4 ? 2 : length() <= 9 ? 4 : 8;
            default -> throw new IllegalStateException(usage.name());
        };
    }

    /** Largest absolute value the picture can hold, at {@link #scale()}. */
    public BigDecimal maxValue() {
        if (alphanumeric) {
            throw new IllegalStateException("Alphanumeric PIC has no numeric range: " + text);
        }
        return BigDecimal.TEN.pow(integerDigits).subtract(BigDecimal.ONE.movePointLeft(scale));
    }

    /** Structural equality, so {@code 999} matches {@code 9(3)}. */
    public boolean sameShape(PicClause other) {
        return alphanumeric == other.alphanumeric && signed == other.signed
                && integerDigits == other.integerDigits && scale == other.scale;
    }
}
