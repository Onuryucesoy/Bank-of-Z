package com.ibm.bankofz.core.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * One Db2 unit of work shared by every repository built on it. Nothing is
 * committed until {@link #commit()}; closing without a commit rolls back.
 *
 * <pre>{@code
 * UnitOfWork.execute(ds, uow -> {
 *     new JdbcAccountRepository(uow, "BANKZ").updateBalances(key, avail, actual);
 *     new JdbcProcTranRepository(uow, "BANKZ").insert(audit);   // failure rolls back the balance
 *     return null;
 * });
 * }</pre>
 */
public final class UnitOfWork implements Connections, AutoCloseable {

    /** Work run inside {@link #execute}. */
    @FunctionalInterface
    public interface Work<T> {
        T run(UnitOfWork uow) throws Exception;
    }

    private final Connection connection;
    private final boolean previousAutoCommit;
    private boolean finished;

    private UnitOfWork(Connection connection) throws SQLException {
        this.connection = connection;
        this.previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
    }

    public static UnitOfWork begin(DataSource dataSource) {
        try {
            return new UnitOfWork(dataSource.getConnection());
        } catch (SQLException e) {
            throw new PersistenceException("begin unit of work", e);
        }
    }

    /** Runs {@code work}, commits if it returns normally, rolls back if it throws. */
    public static <T> T execute(DataSource dataSource, Work<T> work) {
        try (UnitOfWork uow = begin(dataSource)) {
            T result;
            try {
                result = work.run(uow);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            uow.commit();
            return result;
        }
    }

    @Override
    public Lease lease() {
        if (finished) {
            throw new IllegalStateException("unit of work already finished");
        }
        return new Lease() {
            @Override
            public Connection connection() {
                return connection;
            }

            @Override
            public void close() {
                // the unit of work owns the connection
            }
        };
    }

    public void commit() {
        finish(true);
    }

    public void rollback() {
        finish(false);
    }

    @Override
    public void close() {
        try {
            if (!finished) {
                rollback();
            }
        } finally {
            try {
                connection.setAutoCommit(previousAutoCommit);
                connection.close();
            } catch (SQLException e) {
                throw new PersistenceException("close unit of work", e);
            }
        }
    }

    private void finish(boolean commit) {
        if (finished) {
            throw new IllegalStateException("unit of work already finished");
        }
        finished = true;
        try {
            if (commit) {
                connection.commit();
            } else {
                connection.rollback();
            }
        } catch (SQLException e) {
            throw new PersistenceException(commit ? "commit" : "rollback", e);
        }
    }
}
