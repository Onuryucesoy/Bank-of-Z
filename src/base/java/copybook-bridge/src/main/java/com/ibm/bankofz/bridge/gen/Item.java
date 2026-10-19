package com.ibm.bankofz.bridge.gen;

import com.ibm.bankofz.bridge.runtime.FieldSpec;
import com.ibm.bankofz.bridge.runtime.ZonedDecimal;

import java.util.ArrayList;
import java.util.List;

/** A parsed copybook data description entry (levels 01-49 and 77) plus its computed storage. */
final class Item {
    int level;
    String name;
    String redefines;
    String picture;
    String usage;
    ZonedDecimal.Sign signClause;
    int occursMin;
    int occursMax;
    String dependingOn;
    List<Literal> values = new ArrayList<>();
    final List<Item> children = new ArrayList<>();
    final List<Condition> conditions = new ArrayList<>();
    Item parent;
    int line;

    int offset;
    int size;
    FieldSpec.Kind kind;
    int digits;
    int scale;
    boolean signed;
    ZonedDecimal.Sign sign = ZonedDecimal.Sign.NONE;
    String expandedPicture;
    boolean underRedefines;
    String javaName;

    boolean isFiller() {
        return name == null || name.equals("FILLER");
    }

    boolean isGroup() {
        return !children.isEmpty();
    }

    int occurrences() {
        return occursMax == 0 ? 1 : occursMax;
    }

    String effectiveUsage() {
        for (Item i = this; i != null; i = i.parent) {
            if (i.usage != null) {
                return i.usage;
            }
        }
        return "DISPLAY";
    }

    @Override
    public String toString() {
        return level + " " + (name == null ? "FILLER" : name) + " (line " + line + ")";
    }

    record Condition(String name, List<Literal[]> ranges, int line) {
    }

    record Literal(Type type, String text) {
        enum Type { STRING, HEX, NUMBER, SPACE, ZERO, LOW_VALUE, HIGH_VALUE }
    }
}
