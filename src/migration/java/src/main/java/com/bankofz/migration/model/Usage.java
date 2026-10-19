package com.bankofz.migration.model;

/** COBOL {@code USAGE} of an elementary item, as written in the copybook. */
public enum Usage {
    /** Zoned decimal / character, one byte per digit or character (default). */
    DISPLAY,
    /** {@code COMP} / {@code BINARY}: big-endian binary, truncated to the PIC. */
    COMP,
    /** {@code COMP-3} / {@code PACKED-DECIMAL}: two digits per byte plus sign nibble. */
    COMP_3,
    /** {@code COMP-5}: native binary, full range of the halfword/fullword/doubleword. */
    COMP_5;

    /** Parses a copybook usage keyword; {@code null} means DISPLAY. */
    public static Usage fromCobol(String keyword) {
        if (keyword == null) {
            return DISPLAY;
        }
        return switch (keyword.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DISPLAY" -> DISPLAY;
            case "COMP", "COMP-4", "BINARY", "COMPUTATIONAL", "COMPUTATIONAL-4" -> COMP;
            case "COMP-3", "PACKED-DECIMAL", "COMPUTATIONAL-3" -> COMP_3;
            case "COMP-5", "COMPUTATIONAL-5" -> COMP_5;
            default -> throw new IllegalArgumentException("Unsupported USAGE: " + keyword);
        };
    }
}
