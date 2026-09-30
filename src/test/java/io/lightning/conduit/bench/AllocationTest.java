package io.lightning.conduit.bench;

import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Steady-state dispatch through the single-threaded engine must not allocate. */
class AllocationTest {

    private static final int WARMUP = 200_000;
    private static final int MEASURED = 1_000_000;
    /** Slack for the measurement call itself; far below one byte per dispatch. */
    private static final long SLACK_BYTES = 4_096;

    private final Object payload = new Object();

    @Test
    void chain5DoesNotAllocate() {
        Graphs.Tail tail = new Graphs.Tail();
        EventDispatcher<Object> src = Graphs.chain(5, tail);
        assertNoAllocation(src);
        assertEquals(WARMUP + MEASURED, tail.count);
    }

    @Test
    void diamondDoesNotAllocate() {
        Graphs.Join join = new Graphs.Join();
        EventDispatcher<Object> src = Graphs.diamond(join);
        assertNoAllocation(src);
        assertEquals(WARMUP + MEASURED, join.triggers);
    }

    @Test
    void plainListenerDoesNotAllocate() {
        EventDispatcher<Object> src = EventDispatcher.create();
        long[] count = new long[1];
        src.register(e -> count[0]++);
        assertNoAllocation(src);
    }

    private void assertNoAllocation(EventDispatcher<Object> src) {
        boolean supported = ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean m
                && m.isThreadAllocatedMemorySupported();
        Assumptions.assumeTrue(supported, "thread allocation counters not available");
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        mx.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().getId();
        for (int i = 0; i < WARMUP; i++) src.dispatch(payload);

        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < MEASURED; i++) src.dispatch(payload);
        long allocated = mx.getThreadAllocatedBytes(tid) - before;

        assertTrue(allocated < SLACK_BYTES,
                "allocated " + allocated + " bytes over " + MEASURED + " dispatches");
    }
}
