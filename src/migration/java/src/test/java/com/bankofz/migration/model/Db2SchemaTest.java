package com.bankofz.migration.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Executes V001 and V003 against H2 in DB2 mode and compares the resulting
 * CUSTOMER/ACCOUNT columns with the EXEC SQL DECLARE in CUSTDB2.cpy/ACCDB2.cpy.
 * The SQL procedure in V003 is Db2 SQL PL and is not run here; its logic
 * (ALTER SEQUENCE ... RESTART WITH counter + 1) is exercised directly.
 */
class Db2SchemaTest {

    record Column(String name, String type, int length, int scale, boolean nullable) {
    }

    private static final Pattern DECLARE_COLUMN = Pattern.compile(
            "\\b([A-Z0-9_]+)\\s+(CHAR|INTEGER|SMALLINT|DATE|DECIMAL)\\s*"
                    + "(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?(\\s+NOT\\s+NULL)?");

    static Connection freshDatabase() throws Exception {
        Connection c = DriverManager.getConnection(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        run(c, "V001__target_schema.sql");
        run(c, "V003__sequences.sql");
        return c;
    }

    /** Splits a script on its terminator, honouring {@code --#SET TERMINATOR x}. */
    static List<String> statements(String script) throws IOException {
        List<String> out = new ArrayList<>();
        String terminator = ";";
        StringBuilder cur = new StringBuilder();
        for (String line : Files.readAllLines(CopybookParser.REPO_ROOT.resolve("src/migration/db2/" + script))) {
            String trimmed = line.strip();
            if (trimmed.startsWith("--#SET TERMINATOR")) {
                terminator = trimmed.substring("--#SET TERMINATOR".length()).strip();
                continue;
            }
            if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.endsWith(terminator)) {
                cur.append(trimmed, 0, trimmed.length() - terminator.length());
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().strip());
                }
                cur.setLength(0);
            } else {
                cur.append(line).append('\n');
            }
        }
        assertTrue(cur.toString().isBlank(), script + " has an unterminated statement: " + cur);
        return out;
    }

    private static void run(Connection c, String script) throws Exception {
        try (Statement s = c.createStatement()) {
            for (String sql : statements(script)) {
                String head = sql.toUpperCase(Locale.ROOT);
                if (head.startsWith("CREATE PROCEDURE") || head.startsWith("CALL ")) {
                    continue;
                }
                s.execute(sql);
            }
        }
    }

    static List<Column> declared(String copybook) {
        Matcher m = DECLARE_COLUMN.matcher(CopybookParser.sourceText(copybook));
        List<Column> cols = new ArrayList<>();
        while (m.find()) {
            String type = m.group(2);
            int length = switch (type) {
                case "CHAR", "DECIMAL" -> Integer.parseInt(m.group(3));
                default -> 0;
            };
            int scale = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
            cols.add(new Column(m.group(1), type, length, scale, m.group(5) == null));
        }
        return cols;
    }

    static List<Column> actual(Connection c, String table) throws SQLException {
        List<Column> cols = new ArrayList<>();
        try (ResultSet rs = c.createStatement().executeQuery(
                "SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE,"
                        + " IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = '" + table + "'"
                        + " ORDER BY ORDINAL_POSITION")) {
            while (rs.next()) {
                String type = switch (rs.getString(2)) {
                    case "CHARACTER" -> "CHAR";
                    case "NUMERIC", "DECIMAL" -> "DECIMAL";
                    default -> rs.getString(2);
                };
                int length = switch (type) {
                    case "CHAR" -> rs.getInt(3);
                    case "DECIMAL" -> rs.getInt(4);
                    default -> 0;
                };
                int scale = "DECIMAL".equals(type) ? rs.getInt(5) : 0;
                cols.add(new Column(rs.getString(1), type, length, scale, "YES".equals(rs.getString(6))));
            }
        }
        return cols;
    }

    static List<String> primaryKey(Connection c, String table) throws SQLException {
        java.util.SortedMap<Short, String> cols = new java.util.TreeMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, null, table)) {
            while (rs.next()) {
                cols.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(cols.values());
    }

    @ParameterizedTest
    @CsvSource({"CUSTOMER, src/base/cics/copy/CUSTDB2.cpy, 17", "ACCOUNT, src/base/cics/copy/ACCDB2.cpy, 12"})
    void tableMatchesCopybookDeclareExactly(String table, String copybook, int columns) throws Exception {
        List<Column> declared = declared(copybook);
        assertEquals(columns, declared.size(), copybook + " DECLARE parse");
        try (Connection c = freshDatabase()) {
            assertEquals(declared, actual(c, table));
        }
    }

    @Test
    void primaryKeysAreSortcodeAndNumber() throws Exception {
        try (Connection c = freshDatabase()) {
            assertEquals(List.of("CUSTOMER_SORTCODE", "CUSTOMER_NUMBER"), primaryKey(c, "CUSTOMER"));
            assertEquals(List.of("ACCOUNT_SORTCODE", "ACCOUNT_NUMBER"), primaryKey(c, "ACCOUNT"));
            assertEquals(List.of("TRANSACTION_ID"), primaryKey(c, "BANK_TRANSACTION"));
            assertEquals(List.of("COUNTER_NAME"), primaryKey(c, "BANK_COUNTER"));
        }
    }

    @Test
    void bankTransactionReferencesAccountAndChecksType() throws Exception {
        try (Connection c = freshDatabase(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO ACCOUNT (ACCOUNT_SORTCODE, ACCOUNT_NUMBER, ACCOUNT_AVAILABLE_BALANCE)"
                    + " VALUES ('100000', '00000101', 8830.00)");
            String insert = "INSERT INTO BANK_TRANSACTION (TRANSACTION_ID, ACCOUNT_SORTCODE, ACCOUNT_NUMBER,"
                    + " SOURCE_ACCID, TRANSACTION_TIMESTAMP, TRANSACTION_TYPE, AMOUNT) VALUES (%d, '100000', '%s',"
                    + " 101, TIMESTAMP '2003-07-23 17:56:00.64', '%s', 88300000.00)";
            s.execute(insert.formatted(1010000000001L, "00000101", "d"));
            try (ResultSet rs = s.executeQuery("SELECT REFERENCE_TRANSACTION_ID, AMOUNT FROM BANK_TRANSACTION")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getLong(1));
                assertEquals(2, rs.getBigDecimal(2).scale());
            }
            assertThrows(SQLException.class, () -> s.execute(insert.formatted(2L, "00000999", "d")), "FK");
            assertThrows(SQLException.class, () -> s.execute(insert.formatted(3L, "00000101", "x")), "CHECK");
            assertThrows(SQLException.class, () -> s.execute(insert.formatted(1010000000001L, "00000101", "w")),
                    "PK");
        }
    }

    @Test
    void sequencesMirrorGetNewAndAreSeededFromBankCounter() throws Exception {
        try (Connection c = freshDatabase(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT COUNTER_NAME, COUNTER_VALUE FROM BANK_COUNTER"
                    + " ORDER BY COUNTER_NAME")) {
                assertTrue(rs.next());
                assertEquals("987654-ACCOUNT-LAST", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("BANKZCUST987654  ", rs.getString(1)); // CRECUST NCS-CUST-NO-NAME, 17 bytes
                assertEquals(0, rs.getLong(2));
                assertFalse(rs.next());
            }
            // Same steps as SEED_BANK_SEQUENCES after V002 copied CONTROL.
            s.execute("UPDATE BANK_COUNTER SET COUNTER_VALUE = 42 WHERE COUNTER_NAME = 'BANKZCUST987654  '");
            long last;
            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNTER_VALUE FROM BANK_COUNTER WHERE COUNTER_NAME = 'BANKZCUST987654  '")) {
                rs.next();
                last = rs.getLong(1);
            }
            s.execute("ALTER SEQUENCE CUSTOMER_NUMBER_SEQ RESTART WITH " + (last + 1));
            assertEquals(43, nextValue(s, "CUSTOMER_NUMBER_SEQ"));
            assertEquals(44, nextValue(s, "CUSTOMER_NUMBER_SEQ"));
            assertEquals(1, nextValue(s, "ACCOUNT_NUMBER_SEQ"));

            try (ResultSet rs = s.executeQuery("SELECT SEQUENCE_NAME, MAXIMUM_VALUE, CYCLE_OPTION"
                    + " FROM INFORMATION_SCHEMA.SEQUENCES ORDER BY SEQUENCE_NAME")) {
                assertTrue(rs.next());
                assertEquals("ACCOUNT_NUMBER_SEQ", rs.getString(1));
                assertEquals(99_999_999L, rs.getLong(2)); // PIC 9(8)
                assertEquals("NO", rs.getString(3));
                assertTrue(rs.next());
                assertEquals("CUSTOMER_NUMBER_SEQ", rs.getString(1));
                assertEquals(9_999_999_998L, rs.getLong(2)); // PIC 9(10), 9999999999 reserved by INQCUST
                assertEquals("NO", rs.getString(3));
            }
        }
    }

    @Test
    void sequenceIsExhaustedInsteadOfWrapping() throws Exception {
        try (Connection c = freshDatabase(); Statement s = c.createStatement()) {
            s.execute("ALTER SEQUENCE ACCOUNT_NUMBER_SEQ RESTART WITH 99999999");
            assertEquals(99_999_999L, nextValue(s, "ACCOUNT_NUMBER_SEQ"));
            assertThrows(SQLException.class, () -> nextValue(s, "ACCOUNT_NUMBER_SEQ"));
        }
    }

    private static long nextValue(Statement s, String sequence) throws SQLException {
        try (ResultSet rs = s.executeQuery("VALUES NEXT VALUE FOR " + sequence)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
