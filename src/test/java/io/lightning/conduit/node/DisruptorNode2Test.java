package io.lightning.conduit.node;

import com.lmax.disruptor.BlockingWaitStrategy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class DisruptorNode2Test {

    private static final long AWAIT_TIMEOUT_MS = 3_000;

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Concrete DisruptorNode2 that records events as "1:<value>" / "2:<value>"
     * and counts down a latch after each receipt.
     */
    private static class RecordingNode extends DisruptorNode2<String, Integer> {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch;

        RecordingNode expectEvents(int count) {
            latch = new CountDownLatch(count);
            return this;
        }

        boolean await() throws InterruptedException {
            return latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        protected void onEvent1(String event) {
            log.add("1:" + event);
            latch.countDown();
        }

        @Override
        protected void onEvent2(Integer event) {
            log.add("2:" + event);
            latch.countDown();
        }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void testEachDispatcherRoutesToCorrectHandler() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expectEvents(2);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        dispatcher1.dispatch("hello");
        dispatcher2.dispatch(42);

        assertTrue(node.await(), "not all events delivered in time");
        assertTrue(node.log.contains("1:hello"), "onEvent1 was not called");
        assertTrue(node.log.contains("2:42"), "onEvent2 was not called");
    }

    @Test
    void testDispatcher1EventsOnlyGoToOnEvent1() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();

        List<String> onEvent1Calls = Collections.synchronizedList(new ArrayList<>());
        List<Integer> onEvent2Calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        DisruptorNode2<String, Integer> node = new DisruptorNode2<>() {
            @Override protected void onEvent1(String e) { onEvent1Calls.add(e); latch.countDown(); }
            @Override protected void onEvent2(Integer e) { onEvent2Calls.add(e); }
        };

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        dispatcher1.dispatch("a");
        dispatcher1.dispatch("b");
        dispatcher1.dispatch("c");

        assertTrue(latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(3, onEvent1Calls.size());
        assertTrue(onEvent2Calls.isEmpty());
    }

    @Test
    void testDispatcher2EventsOnlyGoToOnEvent2() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();

        List<String> onEvent1Calls = Collections.synchronizedList(new ArrayList<>());
        List<Integer> onEvent2Calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        DisruptorNode2<String, Integer> node = new DisruptorNode2<>() {
            @Override protected void onEvent1(String e) { onEvent1Calls.add(e); }
            @Override protected void onEvent2(Integer e) { onEvent2Calls.add(e); latch.countDown(); }
        };

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        dispatcher2.dispatch(1);
        dispatcher2.dispatch(2);
        dispatcher2.dispatch(3);

        assertTrue(latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(onEvent1Calls.isEmpty());
        assertEquals(3, onEvent2Calls.size());
    }

    @Test
    void testOrderPreservedWithinSingleDispatcher() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();
        int count = 20;
        RecordingNode node = new RecordingNode().expectEvents(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        for (int i = 0; i < count; i++) {
            dispatcher1.dispatch("msg-" + i);
        }

        assertTrue(node.await(), "not all events delivered in time");

        // Extract only the dispatcher1 events (all events here) and verify sequence
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) expected.add("1:msg-" + i);
        assertEquals(expected, node.log);
    }

    @Test
    void testStartWithoutSubscribeThrowsNullPointerException() {
        DisruptorNode2<String, Integer> node = new DisruptorNode2<>() {
            @Override protected void onEvent1(String e) {}
            @Override protected void onEvent2(Integer e) {}
        };

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testStartWithOnlySubscribe1ThrowsNullPointerException() {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        DisruptorNode2<String, Integer> node = new DisruptorNode2<>() {
            @Override protected void onEvent1(String e) {}
            @Override protected void onEvent2(Integer e) {}
        };
        node.subscribe1(dispatcher1);

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testCustomBufferSizeIsAccepted() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expectEvents(2);

        node.bufferSize(64)
            .waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        dispatcher1.dispatch("ping");
        dispatcher2.dispatch(1);

        assertTrue(node.await(), "events not delivered with custom buffer size");
        assertEquals(2, node.log.size());
    }

    @Test
    void testConcurrentProducersAllEventsDelivered() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();
        int perDispatcher = 50;
        RecordingNode node = new RecordingNode().expectEvents(perDispatcher * 2);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        CountDownLatch startGate = new CountDownLatch(1);

        Thread t1 = new Thread(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < perDispatcher; i++) dispatcher1.dispatch("s" + i);
        });
        Thread t2 = new Thread(() -> {
            try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            for (int i = 0; i < perDispatcher; i++) dispatcher2.dispatch(i);
        });

        t1.start();
        t2.start();
        startGate.countDown();
        t1.join();
        t2.join();

        assertTrue(node.await(), "not all concurrent events delivered");
        assertEquals(perDispatcher * 2, node.log.size());

        long count1 = node.log.stream().filter(e -> e.startsWith("1:")).count();
        long count2 = node.log.stream().filter(e -> e.startsWith("2:")).count();
        assertEquals(perDispatcher, count1, "wrong number of onEvent1 calls");
        assertEquals(perDispatcher, count2, "wrong number of onEvent2 calls");
    }

    @Test
    void testNodeCanForwardEventsToDownstreamNode() throws InterruptedException {
        Dispatcher<String> dispatcher1 = EventDispatcher.create();
        Dispatcher<Integer> dispatcher2 = EventDispatcher.create();
        EventDispatcher<String> outputDispatcher = EventDispatcher.create();

        List<String> downstream = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        DisruptorNode2<String, Integer> node = new DisruptorNode2<>() {
            @Override
            protected void onEvent1(String event) { outputDispatcher.dispatch("str:" + event); }
            @Override
            protected void onEvent2(Integer event) { outputDispatcher.dispatch("int:" + event); }
        };

        Node1<String> consumer = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                downstream.add(event);
                latch.countDown();
            }
        };

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();
        consumer.subscribe1(outputDispatcher).start();

        dispatcher1.dispatch("hello");
        dispatcher2.dispatch(5);

        assertTrue(latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            "downstream did not receive all events");
        assertTrue(downstream.contains("str:hello"));
        assertTrue(downstream.contains("int:5"));
    }
}
