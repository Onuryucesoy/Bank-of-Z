package com.ibm.bankofz.core.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ibm.bankofz.core.persistence.FrozenDdl;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class IdAllocatorTest {

    private static final CounterName CUST = CounterName.customer("987654");

    @Test
    void controlNameMatchesCrecust() {
        assertEquals("BANKZCUST987654", CUST.controlName());
        assertEquals("BANKZACCT987654", CounterName.account("987654").controlName());
        assertEquals(9_999_999_999L, CUST.maxValue());
    }

    @Test
    void controlTableAllocatorIncrementsTheSharedRow() throws Exception {
        DataSource ds = FrozenDdl.newDatabase();
        exec(ds, "INSERT INTO BANKZ.CONTROL VALUES ('BANKZCUST987654', 1000, NULL)");
        IdAllocator ids = new ControlTableIdAllocator(ds, "BANKZ");
        assertEquals(1000, ids.current(CUST));
        assertEquals(1001, ids.allocate(CUST));
        assertEquals(1002, ids.allocate(CUST));
        assertEquals(1002, ids.current(CUST));
    }

    @Test
    void controlTableAllocatorIsUniqueUnderConcurrency() throws Exception {
        DataSource ds = FrozenDdl.newDatabase();
        exec(ds, "INSERT INTO BANKZ.CONTROL VALUES ('BANKZCUST987654', 0, NULL)");
        IdAllocator ids = new ControlTableIdAllocator(ds, "BANKZ");
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 200; i++) {
            pool.submit(() -> seen.add(ids.allocate(CUST)));
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertEquals(200, seen.size());
        assertEquals(200, ids.current(CUST));
    }

    @Test
    void missingControlRowFailsLikeCrecustFailCode4() throws Exception {
        IdAllocator ids = new ControlTableIdAllocator(FrozenDdl.newDatabase(), "BANKZ");
        assertThrows(IdAllocationException.class, () -> ids.allocate(CUST));
    }

    @Test
    void sequenceAllocatorUsesDb2Sequence() throws Exception {
        DataSource ds = FrozenDdl.newDatabase();
        exec(ds, "CREATE SEQUENCE BANKZ.CUSTOMER_NUMBER_SEQ AS BIGINT START WITH 1001 INCREMENT BY 1");
        IdAllocator ids = SequenceIdAllocator.withDefaults(ds, "BANKZ");
        assertEquals(1001, ids.allocate(CUST));
        assertEquals(1002, ids.allocate(CUST));
        assertThrows(IdAllocationException.class, () -> ids.allocate(CounterName.account("987654")));
    }

    @Test
    void inMemoryAllocatorEnforcesPicWidth() {
        CounterName acct = CounterName.account("987654");
        IdAllocator ids = new InMemoryIdAllocator().seed(acct, 99_999_998L);
        assertEquals(99_999_999L, ids.allocate(acct));
        assertThrows(IdAllocationException.class, () -> ids.allocate(acct));
    }

    private static void exec(DataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
