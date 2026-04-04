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

public class DisruptorNode3Test {

    private static final long TIMEOUT_MS = 3_000;

    private static class RecordingNode extends DisruptorNode3<String, Integer, Long> {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch;

        RecordingNode expect(int n) { latch = new CountDownLatch(n); return this; }
        boolean await() throws InterruptedException { return latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS); }

        @Override protected void onEvent1(String e)  { log.add("1:" + e); latch.countDown(); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); latch.countDown(); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); latch.countDown(); }
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expect(3);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("alpha"); d2.dispatch(2); d3.dispatch(3L);

        assertTrue(node.await());
        assertTrue(node.log.contains("1:alpha"));
        assertTrue(node.log.contains("2:2"));
        assertTrue(node.log.contains("3:3"));
    }

    @Test
    void testEachDispatcherOnlyTriggersItsOwnHandler() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();

        List<String>  e1 = Collections.synchronizedList(new ArrayList<>());
        List<Integer> e2 = Collections.synchronizedList(new ArrayList<>());
        List<Long>    e3 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(1);

        DisruptorNode3<String, Integer, Long> node = new DisruptorNode3<>() {
            @Override protected void onEvent1(String v)  { e1.add(v); latch.countDown(); }
            @Override protected void onEvent2(Integer v) { e2.add(v); }
            @Override protected void onEvent3(Long v)    { e3.add(v); }
        };

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("only");
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(1, e1.size());
        assertTrue(e2.isEmpty());
        assertTrue(e3.isEmpty());
    }

    @Test
    void testOrderPreservedWithinSingleDispatcher() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        int count = 15;
        RecordingNode node = new RecordingNode().expect(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            d1.dispatch("m" + i);
            expected.add("1:m" + i);
        }

        assertTrue(node.await());
        assertEquals(expected, node.log);
    }

    @Test
    void testStartWithoutSubscribeThrowsNPE() {
        DisruptorNode3<String, Integer, Long> node = new DisruptorNode3<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testCustomBufferSizeIsAccepted() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expect(3);

        node.bufferSize(64).waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("ping"); d2.dispatch(1); d3.dispatch(1L);
        assertTrue(node.await());
        assertEquals(3, node.log.size());
    }

    @Test
    void testConcurrentProducersAllEventsDelivered() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        int each = 30;
        RecordingNode node = new RecordingNode().expect(each * 3);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        CountDownLatch gate = new CountDownLatch(1);
        Thread t1 = new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d1.dispatch("s" + i); });
        Thread t2 = new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d2.dispatch(i); });
        Thread t3 = new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d3.dispatch((long) i); });

        t1.start(); t2.start(); t3.start();
        gate.countDown();
        t1.join(); t2.join(); t3.join();

        assertTrue(node.await());
        assertEquals(each * 3, node.log.size());
        assertEquals(each, node.log.stream().filter(e -> e.startsWith("1:")).count());
        assertEquals(each, node.log.stream().filter(e -> e.startsWith("2:")).count());
        assertEquals(each, node.log.stream().filter(e -> e.startsWith("3:")).count());
    }

    @Test
    void testForwardToDownstreamNode() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        EventDispatcher<String> out = EventDispatcher.create();
        List<String> downstream = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(3);

        DisruptorNode3<String, Integer, Long> node = new DisruptorNode3<>() {
            @Override protected void onEvent1(String e)  { out.dispatch("1:" + e); }
            @Override protected void onEvent2(Integer e) { out.dispatch("2:" + e); }
            @Override protected void onEvent3(Long e)    { out.dispatch("3:" + e); }
        };
        Node1<String> consumer = new Node1<>() {
            @Override protected void onEvent1(String e) { downstream.add(e); latch.countDown(); }
        };

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).start();
        consumer.subscribe1(out).start();

        d1.dispatch("hi"); d2.dispatch(5); d3.dispatch(9L);
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertTrue(downstream.contains("1:hi"));
        assertTrue(downstream.contains("2:5"));
        assertTrue(downstream.contains("3:9"));
    }
}
