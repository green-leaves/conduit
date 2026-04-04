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

public class DisruptorNode1Test {

    private static final long AWAIT_TIMEOUT_MS = 3_000;

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Concrete DisruptorNode1 that records received events and counts down a latch. */
    private static class RecordingNode extends DisruptorNode1<String> {
        final List<String> received = Collections.synchronizedList(new ArrayList<>());
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
            received.add(event);
            latch.countDown();
        }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void testSingleEventIsDelivered() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expectEvents(1);

        // waitStrategy/bufferSize must be called before subscribe1 (subscribe1 returns Node1, losing DisruptorNode1 type)
        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        dispatcher.dispatch("hello");

        assertTrue(node.await(), "event was not delivered within timeout");
        assertEquals(List.of("hello"), node.received);
    }

    @Test
    void testMultipleEventsAllDelivered() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        int count = 20;
        RecordingNode node = new RecordingNode().expectEvents(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        for (int i = 0; i < count; i++) {
            dispatcher.dispatch("event-" + i);
        }

        assertTrue(node.await(), "not all events were delivered within timeout");
        assertEquals(count, node.received.size());
    }

    @Test
    void testEventsDeliveredInOrder() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        int count = 10;
        RecordingNode node = new RecordingNode().expectEvents(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        List<String> sent = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String e = "msg-" + i;
            sent.add(e);
            dispatcher.dispatch(e);
        }

        assertTrue(node.await(), "not all events delivered within timeout");
        // Single consumer thread — order must be preserved
        assertEquals(sent, node.received);
    }

    @Test
    void testStartWithoutSubscribeThrowsNullPointerException() {
        DisruptorNode1<String> node = new DisruptorNode1<>() {
            @Override
            protected void onEvent1(String event) {}
        };

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testCustomBufferSizeIsAccepted() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expectEvents(1);

        node.bufferSize(64)
            .waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        dispatcher.dispatch("ping");

        assertTrue(node.await(), "event not delivered with custom buffer size");
        assertEquals(List.of("ping"), node.received);
    }

    @Test
    void testEventsFromMultipleProducerThreadsAllDelivered() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        int perThread = 50;
        int threads = 4;
        RecordingNode node = new RecordingNode().expectEvents(perThread * threads);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread thread = new Thread(() -> {
                try { startGate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < perThread; i++) {
                    dispatcher.dispatch("t" + tid + "-e" + i);
                }
            });
            producers.add(thread);
            thread.start();
        }

        startGate.countDown();
        for (Thread t : producers) t.join();

        assertTrue(node.await(), "not all events from concurrent producers delivered");
        assertEquals(perThread * threads, node.received.size());
    }

    @Test
    void testNullEventIsDelivered() throws InterruptedException {
        Dispatcher<String> dispatcher = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expectEvents(1);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(dispatcher)
            .start();

        dispatcher.dispatch(null);

        assertTrue(node.await(), "null event not delivered within timeout");
        assertEquals(1, node.received.size());
        assertNull(node.received.get(0));
    }

    @Test
    void testNodeCanForwardEventsToDownstreamNode() throws InterruptedException {
        Dispatcher<String> upstream = EventDispatcher.create();
        EventDispatcher<String> middle = EventDispatcher.create();
        List<String> finalOutput = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        // Middle node (DisruptorNode1) receives from upstream and forwards via a plain dispatcher
        DisruptorNode1<String> middleNode = new DisruptorNode1<>() {
            @Override
            protected void onEvent1(String event) {
                middle.dispatch(event.toUpperCase());
            }
        };

        // Downstream plain Node1 receives from the middle dispatcher
        Node1<String> downstreamNode = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                finalOutput.add(event);
                latch.countDown();
            }
        };

        middleNode.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(upstream)
            .start();
        downstreamNode.subscribe1(middle).start();

        upstream.dispatch("hello");
        upstream.dispatch("world");

        assertTrue(latch.await(AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            "downstream did not receive events in time");
        assertEquals(List.of("HELLO", "WORLD"), finalOutput);
    }
}
