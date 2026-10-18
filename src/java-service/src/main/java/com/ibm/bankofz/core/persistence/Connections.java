package com.ibm.bankofz.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Where a repository gets its JDBC connection. {@link #autoCommit} gives
 * each statement its own connection and commit; a {@link UnitOfWork} hands
 * every repository the same connection so business rows and their PROCTRAN
 * audit row commit or roll back together, like one CICS unit of work.
 */
@FunctionalInterface
public interface Connections {

    Lease lease() throws SQLException;

    /** A borrowed connection; closing it returns it to its owner. */
    interface Lease extends AutoCloseable {
        Connection connection();

        @Override
        void close() throws SQLException;
    }

    static Connections autoCommit(DataSource dataSource) {
        return () -> {
            Connection c = dataSource.getConnection();
            return new Lease() {
                @Override
                public Connection connection() {
                    return c;
                }

                @Override
                public void close() throws SQLException {
                    c.close();
                }
            };
        };
    }
}
