package com.ibm.bankofz.core.persistence;

import com.ibm.bankofz.core.RepoPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;

/**
 * Builds an H2 (DB2 mode) database from the CREATE TABLE statements in the
 * frozen .setup/jcl/cics/Db2-create.j2, so persistence tests track the real
 * schema. z/OS-only clauses (tablespaces, CARDINALITY ...) are dropped.
 */
public final class FrozenDdl {

    private static final Pattern TABLE = Pattern.compile("CREATE TABLE (BANKZ\\.\\w+) \\((.*?)\\)\\s*IN ", Pattern.DOTALL);

    private FrozenDdl() {
    }

    public static DataSource newDatabase() throws IOException, SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=DB2;DB_CLOSE_DELAY=-1");
        String ddl = Files.readString(RepoPaths.resolve(".setup/jcl/cics/Db2-create.j2"));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA BANKZ");
            Matcher m = TABLE.matcher(ddl);
            int n = 0;
            while (m.find()) {
                s.execute("CREATE TABLE " + m.group(1) + " (" + m.group(2) + ")");
                n++;
            }
            if (n < 4) {
                throw new IllegalStateException("expected CONTROL/CUSTOMER/ACCOUNT/PROCTRAN, found " + n + " tables");
            }
        }
        return ds;
    }
}
