package io.lightning.conduit.graph;

import io.lightning.conduit.Conduit;
import io.lightning.conduit.ConduitException;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.graph.TestNodes.JoinNode;
import io.lightning.conduit.graph.TestNodes.MapNode;
import io.lightning.conduit.graph.TestNodes.Sink;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cycle semantics of the single-threaded engine. */
class GraphEngineTest {

    private final List<String> log = new ArrayList<>();

    @AfterEach
    void restoreDefaults() {
        Conduit.maxChainedCycles(1_000_000);
    }

    // ------------------------------------------------------------------ diamond / glitch freedom

    @Test
    void diamondJoinFiresOncePerTickWithConsistentState() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t + 100);
        MapNode<Integer, Integer> b = new MapNode<>("B", log, t -> t * 2);
        JoinNode<Integer, Integer> hedger = new JoinNode<>();

        a.subscribe1(tick);
        b.subscribe1(tick);
        hedger.subscribe1(a).subscribe2(b);
        // deliberately start the join first: order must come from the graph, not start()
        hedger.start();
        b.start();
        a.start();

        tick.dispatch(1);
        tick.dispatch(2);

        assertEquals(List.of("101,2", "102,4"), hedger.triggers);
        // B was started before A, so at equal rank it runs first
        assertEquals(List.of("2:2", "1:101", "2:4", "1:102"), hedger.inputs);
    }

    @Test
    void deepDiamondEachJoinFiresOnce() {
        //        tick
        //       /    \
        //      A      B
        //      |      |
        //      C      |
        //       \    /
        //        Join
        EventDispatcher<Integer> tick = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t + 1);
        MapNode<Integer, Integer> b = new MapNode<>("B", log, t -> t + 2);
        MapNode<Integer, Integer> c = new MapNode<>("C", log, t -> t * 10);
        JoinNode<Integer, Integer> join = new JoinNode<>();
        join.subscribe1(c).subscribe2(b).start();
        c.subscribe1(a).start();
        a.subscribe1(tick).start();
        b.subscribe1(tick).start();

        tick.dispatch(5);

        assertEquals(List.of("60,7"), join.triggers);
        assertTrue(join.rank() > c.rank());
        assertTrue(c.rank() > a.rank());
    }

    @Test
    void ranksFollowLongestPath() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t);
        MapNode<Integer, Integer> b = new MapNode<>("B", log, t -> t);
        JoinNode<Integer, Integer> j = new JoinNode<>();
        a.subscribe1(src).start();
        b.subscribe1(a).start();
        j.subscribe1(a).subscribe2(b).start();
        assertEquals(1, a.rank());
        assertEquals(2, b.rank());
        assertEquals(3, j.rank());
    }

    @Test
    void siblingsAtSameRankRunInStartOrder() {
        EventDispatcher<String> src = EventDispatcher.create();
        Sink<String> s1 = new Sink<>("s1", log);
        Sink<String> s2 = new Sink<>("s2", log);
        Sink<String> s3 = new Sink<>("s3", log);
        s2.subscribe1(src).start();
        s3.subscribe1(src).start();
        s1.subscribe1(src).start();

        src.dispatch("x");

        assertEquals(List.of("s2:x", "s3:x", "s1:x"), log);
    }

    @Test
    void upstreamRunsBeforeDownstreamRegardlessOfListenerOrder() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t + 1);
        Node2<Integer, Integer> both = new Node2<>() {
            @Override protected void onEvent1(Integer e) { log.add("both.1:" + e); }
            @Override protected void onEvent2(Integer e) { log.add("both.2:" + e); }
        };
        // "both" listens to src directly *and* to A; it must wait for A
        both.subscribe1(src).subscribe2(a).start();
        a.subscribe1(src).start();

        src.dispatch(1);

        assertEquals(List.of("A:1", "both.1:1", "both.2:2"), log);
    }

    // ------------------------------------------------------------------ inputs within a cycle

    @Test
    void multipleEventsOnOneInputInOneCycleAreAllDeliveredInOrder() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Burst burst = new Burst();
        Sink<Integer> sink = new Sink<>("sink", log);
        burst.subscribe1(src).start();
        sink.subscribe1(burst).start();

        src.dispatch(3);

        assertEquals(List.of(0, 1, 2), sink.received);
    }

    @Test
    void triggerRunsOncePerCycleAfterAllInputs() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Burst burst = new Burst();
        List<String> seen = new ArrayList<>();
        Node1<Integer> counter = new Node1<>() {
            int sum;
            @Override protected void onEvent1(Integer e) { sum += e; }
            @Override protected boolean onTrigger() { seen.add("sum=" + sum); return true; }
        };
        burst.subscribe1(src).start();
        counter.subscribe1(burst).start();

        src.dispatch(4);

        assertEquals(List.of("sum=6"), seen);
    }

    @Test
    void untouchedNodesDoNotRun() {
        EventDispatcher<String> a = EventDispatcher.create();
        EventDispatcher<String> b = EventDispatcher.create();
        List<String> triggers = new ArrayList<>();
        Node1<String> onA = new Node1<>() {
            @Override protected void onEvent1(String e) { }
            @Override protected boolean onTrigger() { triggers.add("A"); return true; }
        };
        Node1<String> onB = new Node1<>() {
            @Override protected void onEvent1(String e) { }
            @Override protected boolean onTrigger() { triggers.add("B"); return true; }
        };
        onA.subscribe1(a).start();
        onB.subscribe1(b).start();

        a.dispatch("x");

        assertEquals(List.of("A"), triggers);
    }

    // ------------------------------------------------------------------ onTrigger / afterEvent

    @Test
    void triggerReturningFalseDropsOutputAndSkipsDownstream() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Filter evenOnly = new Filter();
        Sink<Integer> sink = new Sink<>("sink", log);
        List<Integer> plain = new ArrayList<>();
        evenOnly.register(plain::add);
        evenOnly.subscribe1(src).start();
        sink.subscribe1(evenOnly).start();

        for (int i = 1; i <= 4; i++) src.dispatch(i);

        assertEquals(List.of(2, 4), sink.received);
        assertEquals(List.of(2, 4), plain);
    }

    @Test
    void afterEventRunsOnceAtEndOfCycleAfterAllNodes() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t) {
            @Override protected void afterEvent() { log.add("A.after"); }
        };
        Sink<Integer> sink = new Sink<>("sink", log) {
            @Override protected void afterEvent() { log.add("sink.after"); }
        };
        a.subscribe1(src).start();
        sink.subscribe1(a).start();

        src.dispatch(1);

        assertEquals(List.of("A:1", "sink:1", "A.after", "sink.after"), log);
    }

    @Test
    void afterEventSkippedWhenTriggerReportsNoChange() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Node1<Integer> node = new Node1<>() {
            int value;
            @Override protected void onEvent1(Integer e) { value = e; }
            @Override protected boolean onTrigger() { return value > 0; }
            @Override protected void afterEvent() { log.add("after:" + value); }
        };
        node.subscribe1(src).start();

        src.dispatch(0);
        src.dispatch(7);

        assertEquals(List.of("after:7"), log);
    }

    @Test
    void conflatingNodePublishesOncePerTickFromAfterEvent() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Burst burst = new Burst();
        List<Integer> published = new ArrayList<>();
        Node1<Integer> conflater = new Node1<>() {
            Integer latest;
            @Override protected void onEvent1(Integer e) { latest = e; }
            @Override protected void afterEvent() { published.add(latest); }
        };
        burst.subscribe1(src).start();
        conflater.subscribe1(burst).start();

        src.dispatch(5);
        src.dispatch(2);

        assertEquals(List.of(4, 1), published);
    }

    @Test
    void dispatchFromAfterEventRunsAsNextCycle() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        EventDispatcher<String> out = EventDispatcher.create();
        Node1<Integer> node = new Node1<>() {
            @Override protected void onEvent1(Integer e) { log.add("on:" + e); }
            @Override protected void afterEvent() { log.add("after"); out.dispatch("published"); }
        };
        out.register(e -> log.add("out:" + e));
        node.subscribe1(src).start();

        src.dispatch(1);

        assertEquals(List.of("on:1", "after", "out:published"), log);
    }

    // ------------------------------------------------------------------ re-entrancy

    @Test
    void dispatchUpstreamFromHandlerIsQueuedAsNextCycle() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Node1<Integer> echo = new Node1<>() {
            @Override
            protected void onEvent1(Integer e) {
                log.add("enter:" + e);
                if (e < 3) src.dispatch(e + 1);   // feeds its own source
                log.add("exit:" + e);
            }
        };
        echo.subscribe1(src).start();

        src.dispatch(1);

        assertEquals(List.of("enter:1", "exit:1", "enter:2", "exit:2", "enter:3", "exit:3"), log);
    }

    @Test
    void followUpCyclesRunInFifoOrderBeforeDispatchReturns() {
        EventDispatcher<String> src = EventDispatcher.create();
        EventDispatcher<String> side = EventDispatcher.create();
        Node1<String> fanOut = new Node1<>() {
            @Override
            protected void onEvent1(String e) {
                side.dispatch(e + "-a");
                side.dispatch(e + "-b");
                log.add("fanOut:" + e);
            }
        };
        Sink<String> sink = new Sink<>("sink", log);
        fanOut.subscribe1(src).start();
        sink.subscribe1(side).start();

        src.dispatch("x");

        assertEquals(List.of("fanOut:x", "sink:x-a", "sink:x-b"), log);
    }

    @Test
    void feedbackLoopThroughPlainDispatcherIsBounded() {
        Conduit.maxChainedCycles(100);
        EventDispatcher<Integer> src = EventDispatcher.create();
        Node1<Integer> loop = new Node1<>() {
            @Override protected void onEvent1(Integer e) { src.dispatch(e + 1); }
        };
        loop.subscribe1(src).start();

        ConduitException ex = assertThrows(ConduitException.class, () -> src.dispatch(0));
        assertTrue(ex.getMessage().contains("chained cycles"));

        // the engine is clean afterwards
        loop.stop();
        List<Integer> got = new ArrayList<>();
        src.register(got::add);
        src.dispatch(42);
        assertEquals(List.of(42), got);
    }

    @Test
    void longChainDoesNotGrowTheStack() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        EventDispatcher<Integer> prev = src;
        for (int i = 0; i < 2_000; i++) {
            MapNode<Integer, Integer> n = new MapNode<>("n" + i, new ArrayList<>(), v -> v + 1);
            n.subscribe1(prev).start();
            prev = n;
        }
        Sink<Integer> sink = new Sink<>("sink", log);
        sink.subscribe1(prev).start();

        src.dispatch(0);

        assertEquals(List.of(2_000), sink.received);
        assertEquals(2_001, sink.rank());
    }

    @Test
    void graphsOnDifferentThreadsAreIndependent() throws InterruptedException {
        List<Throwable> failures = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(new Thread(() -> {
                try {
                    List<String> own = new ArrayList<>();
                    EventDispatcher<Integer> tick = EventDispatcher.create();
                    MapNode<Integer, Integer> a = new MapNode<>("A", own, v -> v + 100);
                    MapNode<Integer, Integer> b = new MapNode<>("B", own, v -> v * 2);
                    JoinNode<Integer, Integer> j = new JoinNode<>();
                    a.subscribe1(tick).start();
                    b.subscribe1(tick).start();
                    j.subscribe1(a).subscribe2(b).start();
                    for (int i = 0; i < 10_000; i++) tick.dispatch(i);
                    assertEquals(10_000, j.triggers.size());
                    assertEquals("10099,19998", j.triggers.get(9_999));
                } catch (Throwable e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();
        assertTrue(failures.isEmpty(), failures::toString);
    }

    // ------------------------------------------------------------------ plain listeners

    @Test
    void plainListenerOnNodeOutputSeesEventAfterNodeRuns() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t * 3);
        a.register(v -> log.add("plain:" + v));
        a.subscribe1(src).start();

        src.dispatch(2);

        assertEquals(List.of("A:2", "plain:6"), log);
    }

    @Test
    void plainListenerForwardingDownstreamStaysInCycle() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, t -> t);
        MapNode<Integer, Integer> b = new MapNode<>("B", log, t -> t);
        List<String> triggers = new ArrayList<>();
        Sink<Integer> sink = new Sink<>("sink", log) {
            @Override protected boolean onTrigger() { triggers.add("sink"); return true; }
        };
        // plain listener on A pushes into B's output; B ranks above A so it stays in-cycle
        a.register(v -> b.dispatch(v + 1000));
        a.subscribe1(src).start();
        b.subscribe1(a).start();
        sink.subscribe1(b).start();

        src.dispatch(1);

        assertEquals(List.of(1001, 1), sink.received);
        assertEquals(List.of("sink"), triggers);
    }

    // ------------------------------------------------------------------ helpers

    /** Dispatches 0..n-1 for input n. */
    static class Burst extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();

        @Override
        public ListenerRegistry<Integer> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Integer n) {
            for (int i = 0; i < n; i++) dispatch(i);
        }
    }

    /** Forwards even numbers only, using onTrigger as the change gate. */
    static class Filter extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();
        private int value;

        @Override
        public ListenerRegistry<Integer> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Integer e) {
            value = e;
            dispatch(e);
        }

        @Override
        protected boolean onTrigger() {
            return value % 2 == 0;
        }
    }
}
