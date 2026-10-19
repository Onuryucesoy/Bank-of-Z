package com.ibm.bankofz.parity.snapshot;

import com.ibm.bankofz.bridge.copybook.cics.Accdb2;
import com.ibm.bankofz.bridge.copybook.cics.Contdb2;
import com.ibm.bankofz.bridge.copybook.cics.Custdb2;
import com.ibm.bankofz.bridge.copybook.cics.Procdb2;
import com.ibm.bankofz.bridge.runtime.Db2Table;
import com.ibm.bankofz.bridge.runtime.Hex;
import com.ibm.bankofz.parity.snapshot.Snapshot.Column;
import com.ibm.bankofz.parity.snapshot.Snapshot.ColumnKind;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Snapshots Db2 tables over JDBC (type 2 under BPXBATCH/JZOS on z/OS, type 4 from anywhere else). Before
 * reading, the live column list is checked against the {@code EXEC SQL DECLARE TABLE} copybook so a
 * DCLGEN that no longer matches the catalog fails loudly instead of producing a misleading diff.
 */
public final class Db2Snapshotter {

    /** {@code keyColumns} empty means the table has no unique key: rows are keyed by their full content. */
    public record TableSpec(String qualifiedName, List<String> keyColumns, Db2Table declared) {
    }

    /** Keys from the unique indexes in {@code .setup/jcl/{cics,ims}/Db2-create.j2}; PROCTRAN has none. */
    public static final List<TableSpec> BANKZ_TABLES = List.of(
            new TableSpec("BANKZ.ACCOUNT", List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"), Accdb2.TABLE),
            new TableSpec("BANKZ.CONTROL", List.of("CONTROL_NAME"), Contdb2.TABLE),
            new TableSpec("BANKZ.CUSTOMER", List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"), Custdb2.TABLE),
            new TableSpec("BANKZ.PROCTRAN", List.of(), Procdb2.TABLE),
            new TableSpec("IMSBANK.HISTORY", List.of("TXID"), null));

    private Db2Snapshotter() {
    }

    public static Snapshot snapshot(Connection con, List<TableSpec> specs) throws SQLException {
        Snapshot s = new Snapshot();
        for (TableSpec spec : specs) {
            snapshot(con, spec, s);
        }
        return s;
    }

    public static void snapshot(Connection con, TableSpec spec, Snapshot into) throws SQLException {
        String order = spec.keyColumns().isEmpty() ? "" : " ORDER BY " + String.join(", ", spec.keyColumns());
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM " + spec.qualifiedName() + order)) {
            ResultSetMetaData md = rs.getMetaData();
            List<Column> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(new Column(md.getColumnName(i).toUpperCase(java.util.Locale.ROOT), kind(md.getColumnType(i))));
            }
            checkDeclared(spec, cols, md);
            Snapshot.Table t = into.table(spec.qualifiedName(), cols);
            while (rs.next()) {
                List<String> vals = new ArrayList<>();
                for (int i = 1; i <= cols.size(); i++) {
                    vals.add(value(rs, i, cols.get(i - 1).kind()));
                }
                t.put(key(spec, cols, vals), vals);
            }
        }
    }

    private static void checkDeclared(TableSpec spec, List<Column> live, ResultSetMetaData md) throws SQLException {
        if (spec.declared() == null) {
            return;
        }
        List<String> liveNames = live.stream().map(Column::name).toList();
        if (!liveNames.equals(spec.declared().columnNames())) {
            throw new IllegalStateException(spec.qualifiedName() + " columns " + liveNames
                    + " do not match " + spec.declared().source() + " " + spec.declared().columnNames());
        }
        for (int i = 0; i < live.size(); i++) {
            Db2Table.Column d = spec.declared().columns().get(i);
            if (d.type().equals("DECIMAL") && (md.getPrecision(i + 1) != d.length() || md.getScale(i + 1) != d.scale())) {
                throw new IllegalStateException(spec.qualifiedName() + "." + d.name() + " is DECIMAL("
                        + md.getPrecision(i + 1) + "," + md.getScale(i + 1) + ") but " + spec.declared().source()
                        + " declares DECIMAL(" + d.length() + "," + d.scale() + ")");
            }
        }
    }

    private static String key(TableSpec spec, List<Column> cols, List<String> vals) {
        Map<String, String> m = Snapshot.asMap(cols, vals);
        List<String> parts = new ArrayList<>();
        if (spec.keyColumns().isEmpty()) {
            for (String v : vals) {
                parts.add(v == null ? Snapshot.NULL : v);
            }
        } else {
            for (String k : spec.keyColumns()) {
                parts.add(m.get(k));
            }
        }
        return String.join("|", parts);
    }

    static ColumnKind kind(int sqlType) {
        return switch (sqlType) {
            case Types.DECIMAL, Types.NUMERIC -> ColumnKind.DECIMAL;
            case Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.TINYINT -> ColumnKind.INTEGER;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ColumnKind.BINARY;
            default -> ColumnKind.TEXT;
        };
    }

    private static String value(ResultSet rs, int i, ColumnKind kind) throws SQLException {
        String v = switch (kind) {
            case DECIMAL -> {
                BigDecimal d = rs.getBigDecimal(i);
                yield d == null ? null : d.toPlainString();
            }
            case BINARY -> {
                byte[] b = rs.getBytes(i);
                yield b == null ? null : Hex.of(b);
            }
            default -> rs.getString(i);
        };
        return rs.wasNull() ? null : v;
    }
}
