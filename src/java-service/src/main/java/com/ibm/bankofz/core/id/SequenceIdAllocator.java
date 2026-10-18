package com.ibm.bankofz.core.id;

import com.ibm.bankofz.core.model.SortCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Post-cutover allocator using Db2 SEQUENCE objects (no hot CONTROL row,
 * no ENQ). Sequences must be created with {@code START WITH} = current
 * CONTROL_VALUE_NUM + 1 at cutover; see
 * src/java-service/src/main/resources/db2/id-sequences.sql. Sequence values
 * are never reused; gaps occur on rollback or cache loss, as with CONTROL.
 */
public final class SequenceIdAllocator implements IdAllocator {

    private final DataSource dataSource;
    private final String schema;
    private final Map<String, String> sequenceByPrefix;

    public SequenceIdAllocator(DataSource dataSource, String schema, Map<String, String> sequenceByPrefix) {
        this.dataSource = dataSource;
        this.schema = schema;
        this.sequenceByPrefix = Map.copyOf(sequenceByPrefix);
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

    private long nextValue(CounterName counter) {
        String seq = sequenceByPrefix.get(counter.prefix());
        if (seq == null) {
            throw new IdAllocationException("no sequence configured for " + counter.prefix());
        }
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
