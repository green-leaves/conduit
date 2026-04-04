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

public class DisruptorNode5Test {

    private static final long TIMEOUT_MS = 3_000;

    private static class RecordingNode extends DisruptorNode5<String, Integer, Long, Double, Boolean> {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch;

        RecordingNode expect(int n) { latch = new CountDownLatch(n); return this; }
        boolean await() throws InterruptedException { return latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS); }

        @Override protected void onEvent1(String e)  { log.add("1:" + e); latch.countDown(); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); latch.countDown(); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); latch.countDown(); }
        @Override protected void onEvent4(Double e)  { log.add("4:" + e); latch.countDown(); }
        @Override protected void onEvent5(Boolean e) { log.add("5:" + e); latch.countDown(); }
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        Dispatcher<Double>  d4 = EventDispatcher.create();
        Dispatcher<Boolean> d5 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expect(5);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();

        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0); d5.dispatch(true);

        assertTrue(node.await());
        assertTrue(node.log.contains("1:a"));
        assertTrue(node.log.contains("2:2"));
        assertTrue(node.log.contains("3:3"));
        assertTrue(node.log.contains("4:4.0"));
        assertTrue(node.log.contains("5:true"));
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        Dispatcher<Double>  d4 = EventDispatcher.create();
        Dispatcher<Boolean> d5 = EventDispatcher.create();

        List<Boolean> e5 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        DisruptorNode5<String, Integer, Long, Double, Boolean> node = new DisruptorNode5<>() {
            @Override protected void onEvent1(String v)  { fail("unexpected onEvent1"); }
            @Override protected void onEvent2(Integer v) { fail("unexpected onEvent2"); }
            @Override protected void onEvent3(Long v)    { fail("unexpected onEvent3"); }
            @Override protected void onEvent4(Double v)  { fail("unexpected onEvent4"); }
            @Override protected void onEvent5(Boolean v) { e5.add(v); latch.countDown(); }
        };
        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();

        d5.dispatch(true); d5.dispatch(false);
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(2, e5.size());
    }

    @Test
    void testOrderPreservedWithinSingleDispatcher() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        Dispatcher<Double>  d4 = EventDispatcher.create();
        Dispatcher<Boolean> d5 = EventDispatcher.create();
        int count = 20;
        RecordingNode node = new RecordingNode().expect(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) { d1.dispatch("m" + i); expected.add("1:m" + i); }

        assertTrue(node.await());
        assertEquals(expected, node.log);
    }

    @Test
    void testStartWithoutSubscribeThrowsNPE() {
        DisruptorNode5<String, Integer, Long, Double, Boolean> node = new DisruptorNode5<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
            @Override protected void onEvent4(Double v)  {}
            @Override protected void onEvent5(Boolean v) {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testConcurrentProducersAllEventsDelivered() throws InterruptedException {
        Dispatcher<String>  d1 = EventDispatcher.create();
        Dispatcher<Integer> d2 = EventDispatcher.create();
        Dispatcher<Long>    d3 = EventDispatcher.create();
        Dispatcher<Double>  d4 = EventDispatcher.create();
        Dispatcher<Boolean> d5 = EventDispatcher.create();
        int each = 20;
        RecordingNode node = new RecordingNode().expect(each * 5);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();

        CountDownLatch gate = new CountDownLatch(1);
        Thread[] threads = {
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d1.dispatch("s" + i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d2.dispatch(i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d3.dispatch((long) i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d4.dispatch((double) i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d5.dispatch(i % 2 == 0); })
        };
        for (Thread t : threads) t.start();
        gate.countDown();
        for (Thread t : threads) t.join();

        assertTrue(node.await());
        assertEquals(each * 5, node.log.size());
        for (int slot = 1; slot <= 5; slot++) {
            final String prefix = slot + ":";
            assertEquals(each, node.log.stream().filter(e -> e.startsWith(prefix)).count());
        }
    }
}
