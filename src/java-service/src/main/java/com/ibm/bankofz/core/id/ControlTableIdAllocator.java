package com.ibm.bankofz.core.id;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Coexistence-mode allocator over the existing BANKZ.CONTROL table, so Java
 * and COBOL draw from the same counter row ({@code CONTROL_NAME =
 * 'BANKZCUST987654'}). The CICS ENQ is replaced by an atomic
 * {@code UPDATE ... SET CONTROL_VALUE_NUM = CONTROL_VALUE_NUM + 1}, whose Db2
 * row lock serialises Java callers; the increment commits in its own unit of
 * work, so a later failure leaves a gap (COBOL also never decrements).
 *
 * <p>COBOL CRECUST reads the row without a lock before updating it. Until
 * CRECUST is retired, only one side may allocate for a given counter — see
 * docs/migration/invariants.md.
 */
public final class ControlTableIdAllocator implements IdAllocator {

    private final DataSource dataSource;
    private final String table;

    public ControlTableIdAllocator(DataSource dataSource, String schema) {
        this.dataSource = dataSource;
        this.table = schema + ".CONTROL";
    }

    @Override
    public long allocate(CounterName counter) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                long next;
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE " + table + " SET CONTROL_VALUE_NUM = CONTROL_VALUE_NUM + 1 WHERE CONTROL_NAME = ?")) {
                    up.setString(1, counter.controlName());
                    if (up.executeUpdate() != 1) {
                        throw new IdAllocationException("no CONTROL row for " + counter.controlName());
                    }
                }
                next = read(c, counter);
                if (next > counter.maxValue()) {
                    throw new IdAllocationException(counter.controlName() + " exhausted PIC 9(" + counter.maxDigits() + ")");
                }
                c.commit();
                return next;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new IdAllocationException("CONTROL update failed for " + counter.controlName(), e);
        }
    }

    @Override
    public long current(CounterName counter) {
        try (Connection c = dataSource.getConnection()) {
            return read(c, counter);
        } catch (SQLException e) {
            throw new IdAllocationException("CONTROL read failed for " + counter.controlName(), e);
        }
    }

    private long read(Connection c, CounterName counter) throws SQLException {
        try (PreparedStatement q = c.prepareStatement(
                "SELECT CONTROL_VALUE_NUM FROM " + table + " WHERE CONTROL_NAME = ?")) {
            q.setString(1, counter.controlName());
            try (ResultSet rs = q.executeQuery()) {
                if (!rs.next()) {
                    throw new IdAllocationException("no CONTROL row for " + counter.controlName());
                }
                return rs.getLong(1);
            }
        }
    }
}
