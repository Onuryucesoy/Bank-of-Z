package com.ibm.bankofz.core.id;

import com.ibm.bankofz.core.model.SortCode;
import com.ibm.bankofz.core.persistence.SqlIdentifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Post-cutover allocator using Db2 SEQUENCE objects (no hot CONTROL row,
 * no ENQ, BIGINT so the full PIC 9(10) range is usable). Create the
 * sequences with {@link #createFromControl}, which starts each one at its
 * CONTROL_VALUE_NUM + 1 so existing customer/account numbers are never
 * reissued. Values are never reused; gaps occur on rollback or cache loss,
 * as with CONTROL.
 */
public final class SequenceIdAllocator implements IdAllocator {

    private final DataSource dataSource;
    private final String schema;
    private final Map<String, String> sequenceByPrefix;

    public SequenceIdAllocator(DataSource dataSource, String schema, Map<String, String> sequenceByPrefix) {
        this.dataSource = dataSource;
        this.schema = SqlIdentifier.require(schema);
        sequenceByPrefix.values().forEach(SqlIdentifier::require);
        this.sequenceByPrefix = Map.copyOf(sequenceByPrefix);
    }

    /**
     * Cutover step, run once when COBOL stops allocating: for each counter,
     * reads CONTROL_VALUE_NUM and creates the matching sequence starting at
     * that value + 1. Fails if a CONTROL row is missing, so a sequence can
     * never start below numbers already issued.
     */
    public void createFromControl(CounterName... counters) {
        try (Connection c = dataSource.getConnection()) {
            for (CounterName counter : counters) {
                String seq = sequenceName(counter);
                long current;
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT CONTROL_VALUE_NUM FROM " + schema + ".CONTROL WHERE CONTROL_NAME = ?")) {
                    q.setString(1, counter.controlName());
                    try (ResultSet rs = q.executeQuery()) {
                        if (!rs.next()) {
                            throw new IdAllocationException("no CONTROL row for " + counter.controlName());
                        }
                        current = rs.getLong(1);
                    }
                }
                try (PreparedStatement ddl = c.prepareStatement("CREATE SEQUENCE " + schema + "." + seq
                        + " AS BIGINT START WITH " + (current + 1) + " INCREMENT BY 1 MINVALUE 1 MAXVALUE "
                        + counter.maxValue() + " NO CYCLE CACHE 20 ORDER")) {
                    ddl.execute();
                }
            }
            if (!c.getAutoCommit()) {
                c.commit();
            }
        } catch (SQLException e) {
            throw new IdAllocationException("sequence cutover failed", e);
        }
    }

    /** Default names: BANKZ.CUSTOMER_NUMBER_SEQ and BANKZ.ACCOUNT_NUMBER_SEQ. */
    public static SequenceIdAllocator withDefaults(DataSource dataSource, String schema) {
        return new SequenceIdAllocator(dataSource, schema, Map.of(
                CounterName.CUSTOMER_PREFIX, "CUSTOMER_NUMBER_SEQ",
                CounterName.ACCOUNT_PREFIX, "ACCOUNT_NUMBER_SEQ"));
    }

    @Override
    public long allocate(CounterName counter) {
        long v = nextValue(counter);
        if (v > counter.maxValue()) {
            throw new IdAllocationException(counter.controlName() + " exhausted PIC 9(" + counter.maxDigits() + ")");
        }
        return v;
    }

    /** Not supported: Db2 PREVIOUS VALUE is session-scoped; read SYSIBM.SYSSEQUENCES operationally. */
    @Override
    public long current(CounterName counter) {
        throw new UnsupportedOperationException("SEQUENCE high-water mark is not portable; read SYSIBM.SYSSEQUENCES");
    }

    private String sequenceName(CounterName counter) {
        String seq = sequenceByPrefix.get(counter.prefix());
        if (seq == null) {
            throw new IdAllocationException("no sequence configured for " + counter.prefix());
        }
        return seq;
    }

    private long nextValue(CounterName counter) {
        String seq = sequenceName(counter);
        if (!counter.sortCode().equals(SortCode.BANK)) {
            throw new IdAllocationException("sequences are provisioned for sort code "
                    + SortCode.BANK + " only");
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("VALUES NEXT VALUE FOR " + schema + "." + seq);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IdAllocationException("sequence " + seq + " failed", e);
        }
    }
}
