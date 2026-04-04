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

public class DisruptorNode7Test {

    private static final long TIMEOUT_MS = 3_000;

    private static class RecordingNode extends DisruptorNode7<String, Integer, Long, Double, Boolean, Float, Character> {
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile CountDownLatch latch;

        RecordingNode expect(int n) { latch = new CountDownLatch(n); return this; }
        boolean await() throws InterruptedException { return latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS); }

        @Override protected void onEvent1(String e)    { log.add("1:" + e); latch.countDown(); }
        @Override protected void onEvent2(Integer e)   { log.add("2:" + e); latch.countDown(); }
        @Override protected void onEvent3(Long e)      { log.add("3:" + e); latch.countDown(); }
        @Override protected void onEvent4(Double e)    { log.add("4:" + e); latch.countDown(); }
        @Override protected void onEvent5(Boolean e)   { log.add("5:" + e); latch.countDown(); }
        @Override protected void onEvent6(Float e)     { log.add("6:" + e); latch.countDown(); }
        @Override protected void onEvent7(Character e) { log.add("7:" + e); latch.countDown(); }
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() throws InterruptedException {
        Dispatcher<String>    d1 = EventDispatcher.create();
        Dispatcher<Integer>   d2 = EventDispatcher.create();
        Dispatcher<Long>      d3 = EventDispatcher.create();
        Dispatcher<Double>    d4 = EventDispatcher.create();
        Dispatcher<Boolean>   d5 = EventDispatcher.create();
        Dispatcher<Float>     d6 = EventDispatcher.create();
        Dispatcher<Character> d7 = EventDispatcher.create();
        RecordingNode node = new RecordingNode().expect(7);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();

        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0);
        d5.dispatch(true); d6.dispatch(6.0f); d7.dispatch('Z');

        assertTrue(node.await());
        for (String expected : List.of("1:a","2:2","3:3","4:4.0","5:true","6:6.0","7:Z")) {
            assertTrue(node.log.contains(expected), "missing: " + expected);
        }
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() throws InterruptedException {
        Dispatcher<String>    d1 = EventDispatcher.create();
        Dispatcher<Integer>   d2 = EventDispatcher.create();
        Dispatcher<Long>      d3 = EventDispatcher.create();
        Dispatcher<Double>    d4 = EventDispatcher.create();
        Dispatcher<Boolean>   d5 = EventDispatcher.create();
        Dispatcher<Float>     d6 = EventDispatcher.create();
        Dispatcher<Character> d7 = EventDispatcher.create();

        List<Character> e7 = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(2);

        DisruptorNode7<String, Integer, Long, Double, Boolean, Float, Character> node = new DisruptorNode7<>() {
            @Override protected void onEvent1(String v)    { fail("unexpected onEvent1"); }
            @Override protected void onEvent2(Integer v)   { fail("unexpected onEvent2"); }
            @Override protected void onEvent3(Long v)      { fail("unexpected onEvent3"); }
            @Override protected void onEvent4(Double v)    { fail("unexpected onEvent4"); }
            @Override protected void onEvent5(Boolean v)   { fail("unexpected onEvent5"); }
            @Override protected void onEvent6(Float v)     { fail("unexpected onEvent6"); }
            @Override protected void onEvent7(Character v) { e7.add(v); latch.countDown(); }
        };
        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();

        d7.dispatch('X'); d7.dispatch('Y');
        assertTrue(latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertEquals(List.of('X', 'Y'), e7);
    }

    @Test
    void testOrderPreservedWithinSingleDispatcher() throws InterruptedException {
        Dispatcher<String>    d1 = EventDispatcher.create();
        Dispatcher<Integer>   d2 = EventDispatcher.create();
        Dispatcher<Long>      d3 = EventDispatcher.create();
        Dispatcher<Double>    d4 = EventDispatcher.create();
        Dispatcher<Boolean>   d5 = EventDispatcher.create();
        Dispatcher<Float>     d6 = EventDispatcher.create();
        Dispatcher<Character> d7 = EventDispatcher.create();
        int count = 20;
        RecordingNode node = new RecordingNode().expect(count);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();

        List<String> expected = new ArrayList<>();
        for (int i = 0; i < count; i++) { d1.dispatch("m" + i); expected.add("1:m" + i); }

        assertTrue(node.await());
        assertEquals(expected, node.log);
    }

    @Test
    void testStartWithoutSubscribeThrowsNPE() {
        DisruptorNode7<String, Integer, Long, Double, Boolean, Float, Character> node = new DisruptorNode7<>() {
            @Override protected void onEvent1(String v)    {}
            @Override protected void onEvent2(Integer v)   {}
            @Override protected void onEvent3(Long v)      {}
            @Override protected void onEvent4(Double v)    {}
            @Override protected void onEvent5(Boolean v)   {}
            @Override protected void onEvent6(Float v)     {}
            @Override protected void onEvent7(Character v) {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testConcurrentProducersAllEventsDelivered() throws InterruptedException {
        Dispatcher<String>    d1 = EventDispatcher.create();
        Dispatcher<Integer>   d2 = EventDispatcher.create();
        Dispatcher<Long>      d3 = EventDispatcher.create();
        Dispatcher<Double>    d4 = EventDispatcher.create();
        Dispatcher<Boolean>   d5 = EventDispatcher.create();
        Dispatcher<Float>     d6 = EventDispatcher.create();
        Dispatcher<Character> d7 = EventDispatcher.create();
        int each = 15;
        RecordingNode node = new RecordingNode().expect(each * 7);

        node.waitStrategy(new BlockingWaitStrategy())
            .subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();

        CountDownLatch gate = new CountDownLatch(1);
        Thread[] threads = {
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d1.dispatch("s" + i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d2.dispatch(i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d3.dispatch((long) i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d4.dispatch((double) i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d5.dispatch(i % 2 == 0); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d6.dispatch((float) i); }),
            new Thread(() -> { try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } for (int i = 0; i < each; i++) d7.dispatch((char)('A' + i % 26)); })
        };
        for (Thread t : threads) t.start();
        gate.countDown();
        for (Thread t : threads) t.join();

        assertTrue(node.await());
        assertEquals(each * 7, node.log.size());
        for (int slot = 1; slot <= 7; slot++) {
            final String prefix = slot + ":";
            assertEquals(each, node.log.stream().filter(e -> e.startsWith(prefix)).count());
        }
    }
}
