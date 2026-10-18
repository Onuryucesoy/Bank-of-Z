package com.ibm.bankofz.core.id;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe allocator for tests and local development. */
public final class InMemoryIdAllocator implements IdAllocator {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public InMemoryIdAllocator seed(CounterName counter, long current) {
        counters.put(counter.controlName(), new AtomicLong(current));
        return this;
    }

    @Override
    public long allocate(CounterName counter) {
        long v = counters.computeIfAbsent(counter.controlName(), k -> new AtomicLong()).incrementAndGet();
        if (v > counter.maxValue()) {
            throw new IdAllocationException(counter.controlName() + " exhausted PIC 9(" + counter.maxDigits() + ")");
        }
        return v;
    }

    @Override
    public long current(CounterName counter) {
        AtomicLong v = counters.get(counter.controlName());
        return v == null ? 0 : v.get();
    }
}
