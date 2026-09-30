package io.lightning.conduit.node;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import io.lightning.conduit.Conduit;
import io.lightning.conduit.ConduitException;
import io.lightning.conduit.ErrorHandler;
import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle, failure handling and backpressure of Disruptor nodes. */
class DisruptorModeTest {

    private static final long TIMEOUT_MS = 5_000;
    private final List<DisruptorNode1<?>> started = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        started.forEach(DisruptorNode1::stop);
        Conduit.errorHandler(ErrorHandler.NO_OP);
    }

    private <T extends DisruptorNode1<?>> T track(T node) {
        started.add(node);
        return node;
    }

    /** Records every payload and counts down. */
    static class Recorder<T> extends DisruptorNode1<T> {
        final List<T> received = Collections.synchronizedList(new ArrayList<>());
        final List<String> threads = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch latch = new CountDownLatch(0);

        Recorder<T> expect(int n) {
            latch = new CountDownLatch(n);
            return this;
        }

        boolean await() throws InterruptedException {
            return latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        protected void onEvent1(T event) {
            received.add(event);
            threads.add(Thread.currentThread().getName());
            latch.countDown();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void startIsIdempotent() throws InterruptedException {
        EventDispatcher<String> src = EventDispatcher.create();
        Recorder<String> node = track(new Recorder<String>().expect(1));
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src);
        node.start();
        node.start();

        src.dispatch("x");

        assertTrue(node.await());
        Thread.sleep(50);
        assertEquals(List.of("x"), node.received);
        assertEquals(1, src.getListeners().size());
    }

    @Test
    void unwiredStartFailsBeforeCreatingThread() {
        Recorder<String> node = new Recorder<>();
        int before = Thread.activeCount();
        assertThrows(NullPointerException.class, node::start);
        assertFalse(node.isStarted());
        assertTrue(Thread.activeCount() <= before);
    }

    @Test
    void stopDrainsInFlightEventsAndUnsubscribes() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Recorder<Integer> node = new Recorder<Integer>().expect(1_000);
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        for (int i = 0; i < 1_000; i++) src.dispatch(i);
        node.stop();

        assertEquals(1_000, node.received.size(), "stop() must drain published events");
        assertTrue(src.getListeners().isEmpty());
        src.dispatch(-1);
        assertFalse(node.received.contains(-1));
    }

    @Test
    void stopIsIdempotentAndNodeCanRestart() throws InterruptedException {
        EventDispatcher<String> src = EventDispatcher.create();
        Recorder<String> node = track(new Recorder<>());
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();
        node.stop();
        node.stop();

        node.expect(1);
        node.start();
        src.dispatch("again");

        assertTrue(node.await());
    }

    @Test
    void consumerThreadExitsOnStop() throws InterruptedException {
        EventDispatcher<String> src = EventDispatcher.create();
        AtomicReference<Thread> consumer = new AtomicReference<>();
        Recorder<String> node = new Recorder<>();
        node.threadFactory(r -> {
            Thread t = new Thread(r, "test-consumer");
            t.setDaemon(true);
            consumer.set(t);
            return t;
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();
        node.stop();

        consumer.get().join(TIMEOUT_MS);
        assertFalse(consumer.get().isAlive());
    }

    @Test
    void configurationAfterStartIsRejected() {
        Recorder<String> node = track(new Recorder<>());
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(EventDispatcher.create()).start();
        assertThrows(IllegalStateException.class, () -> node.bufferSize(64));
        assertThrows(IllegalStateException.class, () -> node.waitStrategy(new BlockingWaitStrategy()));
        assertThrows(IllegalStateException.class, () -> node.producerType(ProducerType.SINGLE));
    }

    @Test
    void bufferSizeMustBePowerOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> new Recorder<String>().bufferSize(100));
    }

    // ------------------------------------------------------------------ configuration

    @Test
    void threadFactoryIsUsedForConsumer() throws InterruptedException {
        EventDispatcher<String> src = EventDispatcher.create();
        Recorder<String> node = track(new Recorder<String>().expect(1));
        node.threadFactory(r -> {
            Thread t = new Thread(r, "pinned-core-3");
            t.setDaemon(true);
            return t;
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        src.dispatch("x");

        assertTrue(node.await());
        assertEquals(List.of("pinned-core-3"), node.threads);
    }

    @Test
    void defaultThreadIsNamedDaemon() throws InterruptedException {
        EventDispatcher<String> src = EventDispatcher.create();
        AtomicReference<Thread> seen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        DisruptorNode1<String> node = track(new DisruptorNode1<>() {
            @Override
            protected void onEvent1(String e) {
                seen.set(Thread.currentThread());
                latch.countDown();
            }
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        src.dispatch("x");

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(seen.get().isDaemon());
        assertTrue(seen.get().getName().startsWith("conduit-"), seen.get().getName());
    }

    @Test
    void singleProducerTypeDeliversInOrder() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Recorder<Integer> node = track(new Recorder<Integer>().expect(10_000));
        node.producerType(ProducerType.SINGLE).waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        for (int i = 0; i < 10_000; i++) src.dispatch(i);

        assertTrue(node.await());
        for (int i = 0; i < 10_000; i++) assertEquals(i, node.received.get(i));
    }

    // ------------------------------------------------------------------ failures

    @Test
    void handlerFailureDoesNotKillConsumerUnderSkip() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        List<Object> reported = Collections.synchronizedList(new ArrayList<>());
        Conduit.errorHandler((s, e, t) -> reported.add(e));
        Recorder<Integer> node = track(new Recorder<Integer>() {
            @Override
            protected void onEvent1(Integer event) {
                if (event < 0) throw new IllegalStateException("negative");
                super.onEvent1(event);
            }
        }.expect(2));
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.bufferSize(4).waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        src.dispatch(1);
        for (int i = 0; i < 20; i++) src.dispatch(-1);   // more than the ring holds: must not stall
        src.dispatch(2);

        assertTrue(node.await(), "consumer thread died or stalled");
        assertEquals(List.of(1, 2), node.received);
        assertEquals(20, reported.size());
        assertFalse(node.isHalted());
    }

    @Test
    void haltPolicyStopsNodeAndSurfacesToProducers() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        CountDownLatch failed = new CountDownLatch(1);
        Conduit.errorHandler((s, e, t) -> failed.countDown());
        Recorder<Integer> node = track(new Recorder<Integer>() {
            @Override
            protected void onEvent1(Integer event) {
                if (event < 0) throw new IllegalStateException("negative");
                super.onEvent1(event);
            }
        });
        node.bufferSize(4).waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        src.dispatch(-1);
        assertTrue(failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertTrue(node.isHalted());
        ConduitException ex = assertThrows(ConduitException.class, () -> src.dispatch(5));
        assertSame(node, ex.getSource());
        assertTrue(node.received.isEmpty());
    }

    @Test
    void restartClearsHaltedState() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        CountDownLatch failed = new CountDownLatch(1);
        Conduit.errorHandler((s, e, t) -> failed.countDown());
        Recorder<Integer> node = track(new Recorder<Integer>() {
            @Override
            protected void onEvent1(Integer event) {
                if (event < 0) throw new IllegalStateException("negative");
                super.onEvent1(event);
            }
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();
        src.dispatch(-1);
        assertTrue(failed.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        node.stop();
        node.expect(1);
        node.start();
        src.dispatch(7);

        assertTrue(node.await());
        assertFalse(node.isHalted());
    }

    // ------------------------------------------------------------------ backpressure

    @Test
    void dropBackpressureNeverBlocksAndCountsDrops() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger handled = new AtomicInteger();
        DisruptorNode1<Integer> node = track(new DisruptorNode1<>() {
            @Override
            protected void onEvent1(Integer e) {
                try {
                    release.await();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                handled.incrementAndGet();
            }
        });
        node.backpressure(DisruptorComponent.Backpressure.DROP)
            .bufferSize(4).waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) src.dispatch(i);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        release.countDown();

        assertTrue(elapsedMs < TIMEOUT_MS, "producer blocked");
        assertTrue(node.droppedCount() >= 100 - 5, "dropped " + node.droppedCount());
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (handled.get() + node.droppedCount() < 100 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(100, handled.get() + node.droppedCount());
    }

    @Test
    void blockBackpressureLosesNothing() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Recorder<Integer> node = track(new Recorder<Integer>().expect(5_000));
        node.bufferSize(8).waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        for (int i = 0; i < 5_000; i++) src.dispatch(i);

        assertTrue(node.await());
        assertEquals(0, node.droppedCount());
        assertEquals(5_000, node.received.size());
    }

    // ------------------------------------------------------------------ batch hooks and payload release

    @Test
    void triggerAndAfterEventRunAtEndOfBatch() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        CountDownLatch gate = new CountDownLatch(1);
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        DisruptorNode1<Integer> node = track(new DisruptorNode1<>() {
            int sum;

            @Override
            protected void onEvent1(Integer e) {
                if (e == 0) {
                    try {
                        gate.await();    // hold the consumer so the rest pile up into one batch
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                sum += e;
            }

            @Override
            protected boolean onTrigger() {
                log.add("trigger:" + sum);
                return sum >= 10;
            }

            @Override
            protected void afterEvent() {
                log.add("after:" + sum);
                done.countDown();
            }
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        src.dispatch(0);
        for (int i = 1; i <= 4; i++) src.dispatch(i);
        gate.countDown();

        assertTrue(done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals("after:10", log.get(log.size() - 1));
        assertTrue(log.size() <= 3, "hooks should run per batch, not per event: " + log);
    }

    @Test
    void payloadIsReleasedAfterHandling() throws InterruptedException {
        EventDispatcher<Object> src = EventDispatcher.create();
        CountDownLatch handled = new CountDownLatch(1);
        DisruptorNode1<Object> node = track(new DisruptorNode1<>() {
            @Override
            protected void onEvent1(Object e) {
                handled.countDown();
            }
        });
        node.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();
        DisruptorComponent component = componentOf(node);

        src.dispatch(new byte[1024]);
        assertTrue(handled.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        Event<Object> slot = component.disruptor().getRingBuffer().get(0);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (slot.getPayload() != null && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertNull(slot.getPayload(), "ring slot still references the handled payload");

        node.stop();
        assertNull(component.disruptor(), "stopped component releases the Disruptor");
    }

    private static DisruptorComponent componentOf(DisruptorNode1<?> node) {
        try {
            java.lang.reflect.Field f = DisruptorNode1.class.getDeclaredField("disruptor");
            f.setAccessible(true);
            return (DisruptorComponent) f.get(node);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void eventClearDropsPayload() {
        Event<String> e = new Event<>();
        e.set("payload", 3);
        e.clear();
        assertNull(e.getPayload());
        assertEquals(0, e.getType());
    }

    // ------------------------------------------------------------------ thread boundary into a sync graph

    @Test
    void disruptorNodeFeedsSynchronousDiamondOnConsumerThread() throws InterruptedException {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Forwarder hop = track(new Forwarder());
        List<String> joined = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(100);
        Plus a = new Plus(100);
        Plus b = new Plus(1_000);
        Node2<Integer, Integer> join = new Node2<>() {
            Integer x, y;
            @Override protected void onEvent1(Integer e) { x = e; }
            @Override protected void onEvent2(Integer e) { y = e; }
            @Override protected boolean onTrigger() {
                joined.add(x + "," + y);
                latch.countDown();
                return true;
            }
        };
        a.subscribe1(hop).start();
        b.subscribe1(hop).start();
        join.subscribe1(a).subscribe2(b).start();
        hop.waitStrategy(new BlockingWaitStrategy()).subscribe1(src).start();

        for (int i = 0; i < 100; i++) src.dispatch(i);

        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        for (int i = 0; i < 100; i++) assertEquals((i + 100) + "," + (i + 1_000), joined.get(i));
    }

    // ------------------------------------------------------------------ soak

    @Test
    void multiProducerSoakPreservesPerProducerOrder() throws InterruptedException {
        int producers = 4;
        int perProducer = 100_000;
        List<EventDispatcher<long[]>> feeds = new ArrayList<>();
        long[] lastSeen = new long[producers];
        java.util.Arrays.fill(lastSeen, -1);
        AtomicInteger outOfOrder = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(producers * perProducer);
        DisruptorNode1<long[]> node = track(new DisruptorNode1<>() {
            @Override
            protected void onEvent1(long[] e) {
                int p = (int) e[0];
                if (e[1] != lastSeen[p] + 1) outOfOrder.incrementAndGet();
                lastSeen[p] = e[1];
                all.countDown();
            }
        });
        EventDispatcher<long[]> feed = EventDispatcher.create();
        feeds.add(feed);
        node.bufferSize(1024).waitStrategy(new BlockingWaitStrategy()).subscribe1(feed).start();

        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            int id = p;
            threads.add(new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < perProducer; i++) feed.dispatch(new long[]{id, i});
            }));
        }
        threads.forEach(Thread::start);
        go.countDown();
        for (Thread t : threads) t.join();

        assertTrue(all.await(30, TimeUnit.SECONDS), "remaining " + all.getCount());
        assertEquals(0, outOfOrder.get());
        for (long l : lastSeen) assertEquals(perProducer - 1, l);
    }

    /** Disruptor node that re-dispatches its input on its consumer thread. */
    static class Forwarder extends DisruptorNode1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();
        @Override public ListenerRegistry<Integer> listeners() { return listeners; }
        @Override protected void onEvent1(Integer e) { dispatch(e); }
    }

    static class Plus extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();
        private final int delta;
        Plus(int delta) { this.delta = delta; }
        @Override public ListenerRegistry<Integer> listeners() { return listeners; }
        @Override protected void onEvent1(Integer e) { dispatch(e + delta); }
    }
}
