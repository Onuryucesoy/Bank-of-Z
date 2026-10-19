package com.ibm.bankofz.bridge.runtime;

import java.util.List;

/** Table shape taken from an {@code EXEC SQL DECLARE ... TABLE} copybook (CUSTDB2, ACCDB2, PROCDB2, CONTDB2). */
public record Db2Table(String name, String source, List<Column> columns) {
    public record Column(String name, String type, int length, int scale, boolean nullable) {
    }

    public Db2Table {
        columns = List.copyOf(columns);
    }

    public List<String> columnNames() {
        return columns.stream().map(Column::name).toList();
    }
}
