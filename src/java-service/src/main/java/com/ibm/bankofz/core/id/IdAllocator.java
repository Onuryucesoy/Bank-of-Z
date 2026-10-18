package com.ibm.bankofz.core.id;

/**
 * Allocates monotonically increasing numbers for a {@link CounterName},
 * replacing the CRECUST/CREACC "ENQ BANKZCUST+sortcode, read CONTROL, +1,
 * update, DEQ" sequence. Callers must not depend on the mechanism (CONTROL
 * row, Db2 SEQUENCE, in-memory).
 */
public interface IdAllocator {

    /** Returns the next number; never returns the same value twice for a counter. */
    long allocate(CounterName counter);

    /** Highest number allocated so far (CONTROL_VALUE_NUM), without allocating. */
    long current(CounterName counter);
}
