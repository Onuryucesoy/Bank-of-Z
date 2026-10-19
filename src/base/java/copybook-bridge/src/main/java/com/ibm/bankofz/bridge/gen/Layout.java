package com.ibm.bankofz.bridge.gen;

import com.ibm.bankofz.bridge.runtime.Binary;
import com.ibm.bankofz.bridge.runtime.Edited;
import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.PackedDecimal;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Storage mapping for IBM Enterprise COBOL (no SYNC, so no slack bytes). */
final class Layout {
    private static final Pattern REPEAT = Pattern.compile("(.)\\((\\d+)\\)");

    private Layout() {
    }

    static void compute(Item record, String source) {
        record.redefines = null;
        classify(record, source);
        place(record, 0, false, source);
    }

    static String expand(String picture) {
        Matcher m = REPEAT.matcher(picture);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).repeat(Integer.parseInt(m.group(2)))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void classify(Item it, String source) {
        for (Item c : it.children) {
            classify(c, source);
        }
        if (it.isGroup()) {
            if (it.picture != null) {
                throw fail(source, it, "group item with PICTURE");
            }
            it.kind = FieldSpec.Kind.GROUP;
            return;
        }
        String usage = it.effectiveUsage();
        switch (usage) {
            case "POINTER", "FUNCTION-POINTER" -> {
                // AMODE 31: 4-byte addresses
                it.kind = FieldSpec.Kind.POINTER;
                it.size = 4;
                return;
            }
            case "PROCEDURE-POINTER" -> {
                it.kind = FieldSpec.Kind.POINTER;
                it.size = 8;
                return;
            }
            case "INDEX" -> {
                it.kind = FieldSpec.Kind.BINARY;
                it.digits = 9;
                it.signed = true;
                it.size = 4;
                return;
            }
            case "DISPLAY", "BINARY", "COMP-5", "PACKED" -> {
            }
            default -> throw fail(source, it, "USAGE " + usage + " is not supported");
        }
        if (it.picture == null) {
            throw fail(source, it, "elementary item without PICTURE");
        }
        String pic = expand(it.picture);
        it.expandedPicture = pic;
        if (pic.matches("[XA9]*[XA][XA9]*")) {
            if (!usage.equals("DISPLAY")) {
                throw fail(source, it, "alphanumeric item with USAGE " + usage);
            }
            it.kind = FieldSpec.Kind.ALPHANUMERIC;
            it.size = pic.length();
        } else if (pic.matches("S?9*V?9*") && pic.indexOf('9') >= 0) {
            it.signed = pic.startsWith("S");
            int v = pic.indexOf('V');
            it.digits = (int) pic.chars().filter(ch -> ch == '9').count();
            it.scale = v < 0 ? 0 : (int) pic.substring(v).chars().filter(ch -> ch == '9').count();
            switch (usage) {
                case "DISPLAY" -> {
                    it.kind = FieldSpec.Kind.ZONED;
                    it.sign = !it.signed ? ZonedDecimal.Sign.NONE
                            : (it.signClause != null ? it.signClause : ZonedDecimal.Sign.TRAILING);
                    it.size = ZonedDecimal.byteLength(it.digits, it.sign);
                }
                case "PACKED" -> {
                    it.kind = FieldSpec.Kind.PACKED;
                    it.size = PackedDecimal.byteLength(it.digits);
                }
                default -> {
                    if (it.digits > 18) {
                        throw fail(source, it, "binary item with more than 18 digits");
                    }
                    it.kind = usage.equals("COMP-5") ? FieldSpec.Kind.BINARY_NATIVE : FieldSpec.Kind.BINARY;
                    it.size = Binary.byteLength(it.digits);
                }
            }
            if (it.digits > 31) {
                throw fail(source, it, "more than 31 digits");
            }
        } else if (pic.matches("[9Z*.,B0/+\\-$]*(CR|DB)?") && usage.equals("DISPLAY")) {
            it.kind = FieldSpec.Kind.EDITED;
            it.size = pic.length();
            it.digits = Edited.digits(pic);
            it.scale = Edited.scale(pic);
            it.signed = pic.contains("-") || pic.contains("+") || pic.endsWith("CR") || pic.endsWith("DB");
        } else {
            throw fail(source, it, "unsupported PICTURE " + it.picture);
        }
    }

    private static void place(Item it, int offset, boolean underRedefines, String source) {
        it.offset = offset;
        it.underRedefines = underRedefines || it.redefines != null;
        if (!it.isGroup()) {
            return;
        }
        int cursor = offset;
        for (int i = 0; i < it.children.size(); i++) {
            Item child = it.children.get(i);
            if (child.redefines != null) {
                Item target = null;
                for (int j = i - 1; j >= 0; j--) {
                    Item s = it.children.get(j);
                    if (child.redefines.equals(s.name)) {
                        target = s;
                        break;
                    }
                }
                if (target == null) {
                    throw fail(source, child, "REDEFINES target " + child.redefines + " not found among siblings");
                }
                if (target.occursMax != 0 || child.occursMax != 0 && child.dependingOn != null) {
                    throw fail(source, child, "REDEFINES of/with a table is not supported");
                }
                place(child, target.offset, true, source);
                if (child.size * child.occurrences() > target.size * target.occurrences()) {
                    throw fail(source, child, "redefining item (" + child.size * child.occurrences()
                            + " bytes) is larger than " + target.name + " (" + target.size + " bytes)");
                }
            } else {
                place(child, cursor, it.underRedefines, source);
                cursor += child.size * child.occurrences();
            }
        }
        it.size = cursor - offset;
    }

    static IllegalStateException fail(String source, Item it, String msg) {
        return new IllegalStateException(source + ":" + it.line + " " + it + ": " + msg);
    }
}
