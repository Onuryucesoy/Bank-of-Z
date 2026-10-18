package com.ibm.bankofz.migration.codec;

import java.util.List;

/**
 * Flat list of elementary items in a fixed-length record. Group levels, REDEFINES overlays and OCCURS arrays
 * are flattened by the caller (Phase 2 generates layouts from {@code copybook-field-mapping.json}).
 */
public record RecordLayout(String name, int length, List<FieldSpec> fields) {

    public RecordLayout {
        fields = List.copyOf(fields);
        for (FieldSpec f : fields) {
            if (f.end() > length) {
                throw new IllegalArgumentException(name + "." + f.name() + " ends at " + f.end() + " > record length " + length);
            }
        }
    }
}
