package io.lightning.conduit.graph;

import io.lightning.conduit.ConduitException;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.graph.TestNodes.MapNode;
import io.lightning.conduit.graph.TestNodes.Sink;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** start/stop, wiring validation and cycle detection. */
class LifecycleTest {

    private final List<String> log = new ArrayList<>();

    @Test
    void startIsIdempotent() {
        EventDispatcher<String> src = EventDispatcher.create();
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(src);
        sink.start();
        sink.start();
        sink.start();

        src.dispatch("x");

        assertEquals(List.of("x"), sink.received);
        assertEquals(1, src.getListeners().size());
    }

    @Test
    void unwiredInputFailsFastWithDescriptiveMessage() {
        Node2<String, String> node = new Node2<>() {
            @Override protected void onEvent1(String e) { }
            @Override protected void onEvent2(String e) { }
        };
        node.subscribe1(EventDispatcher.create());

        NullPointerException ex = assertThrows(NullPointerException.class, node::start);
        assertTrue(ex.getMessage().contains("input 2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("subscribe2"), ex.getMessage());
        assertFalse(node.isStarted());
    }

    @Test
    void failedStartRegistersNothing() {
        EventDispatcher<String> src = EventDispatcher.create();
        Node2<String, String> node = new Node2<>() {
            @Override protected void onEvent1(String e) { log.add(e); }
            @Override protected void onEvent2(String e) { }
        };
        node.subscribe1(src);
        assertThrows(NullPointerException.class, node::start);
        assertTrue(src.getListeners().isEmpty());
    }

    @Test
    void isStartedReflectsLifecycle() {
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(EventDispatcher.create());
        assertFalse(sink.isStarted());
        sink.start();
        assertTrue(sink.isStarted());
        sink.stop();
        assertFalse(sink.isStarted());
    }

    @Test
    void stopUnsubscribesAndIsIdempotent() {
        EventDispatcher<String> src = EventDispatcher.create();
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(src).start();
        sink.stop();
        sink.stop();

        src.dispatch("x");

        assertTrue(sink.received.isEmpty());
        assertTrue(src.getListeners().isEmpty());
    }

    @Test
    void stopBeforeStartIsNoOp() {
        Sink<String> sink = new Sink<>("s", log);
        assertDoesNotThrow(sink::stop);
    }

    @Test
    void stoppedNodeCanBeRewiredAndRestarted() {
        EventDispatcher<String> first = EventDispatcher.create();
        EventDispatcher<String> second = EventDispatcher.create();
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(first).start();
        sink.stop();
        sink.subscribe1(second).start();

        first.dispatch("old");
        second.dispatch("new");

        assertEquals(List.of("new"), sink.received);
    }

    @Test
    void rewiringAStartedNodeIsRejected() {
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(EventDispatcher.create()).start();
        assertThrows(IllegalStateException.class, () -> sink.subscribe1(EventDispatcher.create()));
    }

    @Test
    void stoppingMidGraphCutsDownstreamDelivery() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, v -> v);
        Sink<Integer> sink = new Sink<>("sink", log);
        a.subscribe1(src).start();
        sink.subscribe1(a).start();
        a.stop();

        src.dispatch(1);

        assertTrue(log.isEmpty());
    }

    @Test
    void directCycleIsRejectedAtStart() {
        Loop a = new Loop("A");
        Loop b = new Loop("B");
        a.subscribe1(b);
        b.subscribe1(a);

        ConduitException ex = assertThrows(ConduitException.class, a::start);
        assertTrue(ex.getMessage().contains("cycle"), ex.getMessage());
        assertFalse(a.isStarted());
    }

    @Test
    void cycleClosedByLaterStartIsRejected() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Loop a = new Loop("A");
        Loop b = new Loop("B");
        Loop c = new Loop("C");
        b.subscribe1(a).start();
        c.subscribe1(b).start();
        a.subscribe1(c);            // A <- C <- B <- A

        assertThrows(ConduitException.class, a::start);
        assertFalse(a.isStarted());
        assertNotNull(src);
    }

    @Test
    void selfLoopIsRejected() {
        Loop a = new Loop("A");
        a.subscribe1(a);
        assertThrows(ConduitException.class, a::start);
    }

    @Test
    void startingChildBeforeParentStillOrdersCorrectly() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> a = new MapNode<>("A", log, v -> v + 1);
        MapNode<Integer, Integer> b = new MapNode<>("B", log, v -> v + 1);
        Sink<Integer> sink = new Sink<>("sink", log);
        sink.subscribe1(b).start();
        b.subscribe1(a).start();
        a.subscribe1(src).start();

        src.dispatch(0);

        assertEquals(List.of("A:0", "B:1", "sink:2"), log);
        assertTrue(a.rank() < b.rank() && b.rank() < sink.rank());
    }

    @Test
    void parentRewiredDeeperBumpsAlreadyStartedChildren() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        MapNode<Integer, Integer> x = new MapNode<>("X", log, v -> v);
        MapNode<Integer, Integer> y = new MapNode<>("Y", log, v -> v);
        MapNode<Integer, Integer> mid = new MapNode<>("M", log, v -> v);
        Sink<Integer> sink = new Sink<>("sink", log);
        x.subscribe1(src).start();
        y.subscribe1(x).start();
        // sink starts while mid is still wired to src (rank 1)
        mid.subscribe1(src);
        sink.subscribe1(mid).start();
        assertEquals(2, sink.rank());
        // mid is re-wired below y (rank 3) before it starts: sink must move below it
        mid.subscribe1(y).start();

        assertEquals(3, mid.rank());
        assertEquals(4, sink.rank());
        src.dispatch(1);
        assertEquals(List.of("X:1", "Y:1", "M:1", "sink:1"), log);
    }

    @Test
    void startFromInsideACycleIsRejected() {
        EventDispatcher<String> src = EventDispatcher.create();
        List<Throwable> errors = new ArrayList<>();
        Sink<String> late = new Sink<>("late", log);
        late.subscribe1(EventDispatcher.create());
        Node1<String> starter = new Node1<>() {
            @Override
            protected void onEvent1(String e) {
                try {
                    late.start();
                } catch (IllegalStateException ex) {
                    errors.add(ex);
                }
            }
        };
        starter.subscribe1(src).start();

        src.dispatch("go");

        assertEquals(1, errors.size());
        assertFalse(late.isStarted());
    }

    @Test
    void toStringShowsRankAndState() {
        Sink<String> sink = new Sink<>("s", log);
        sink.subscribe1(EventDispatcher.create()).start();
        assertTrue(sink.toString().contains("rank=1"), sink.toString());
        assertTrue(sink.toString().contains("started"), sink.toString());
    }

    /** A node whose output type equals its input type, so it can be wired into loops. */
    static class Loop extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();
        final String name;

        Loop(String name) {
            this.name = name;
        }

        @Override
        public ListenerRegistry<Integer> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Integer e) {
            dispatch(e);
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
