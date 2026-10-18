package com.ibm.bankofz.core.copybook;

import java.util.List;

/**
 * One data item of a copybook after layout. {@code offset} is relative to the
 * start of the 01 record; for items inside an OCCURS it is the offset of the
 * first occurrence and {@code stride} is the size of one occurrence of the
 * nearest enclosing OCCURS.
 */
public record Field(
        int level,
        String name,
        Pic pic,
        Usage usage,
        boolean signSeparate,
        boolean signLeading,
        int offset,
        int length,
        int occursMax,
        String occursDependingOn,
        String redefines,
        boolean underRedefines,
        List<Field> children) {

    public boolean isGroup() {
        return pic == null && usage != Usage.POINTER;
    }

    public boolean isFiller() {
        return name.equals("FILLER");
    }

    /** Java type a migrated model should use for this item. */
    public String javaType() {
        if (isGroup()) {
            return "group";
        }
        if (usage == Usage.POINTER) {
            return "pointer (not migrated)";
        }
        if (!pic.numeric()) {
            return "String";
        }
        if (pic.scale() > 0 || usage == Usage.PACKED) {
            return "BigDecimal";
        }
        if (usage == Usage.NATIVE_BINARY || usage == Usage.BINARY) {
            return pic.digits() <= 4 ? "short" : pic.digits() <= 9 ? "int" : "long";
        }
        return pic.digits() <= 9 ? "int" : "long";
    }
}
