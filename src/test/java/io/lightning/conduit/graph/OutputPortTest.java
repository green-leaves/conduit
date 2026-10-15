package io.lightning.conduit.graph;

import io.lightning.conduit.Conduit;
import io.lightning.conduit.ErrorHandler;
import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.Output;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Nodes publishing through {@link Output} ports instead of implementing {@code EventDispatcher}. */
class OutputPortTest {

    @AfterEach
    void restoreDefaults() {
        Conduit.defaultErrorPolicy(ErrorPolicy.HALT);
        Conduit.errorHandler(ErrorHandler.NO_OP);
    }

    /** One input, two outputs of different types. */
    static class Splitter extends Node1<Integer> {
        final Output<Integer> numbers = output();
        final Output<String> labels = output();

        @Override
        protected void onEvent1(Integer e) {
            numbers.dispatch(e + 1);
            labels.dispatch("L" + e);
        }
    }

    static class PortMap extends Node1<Integer> {
        final Output<Integer> out = output();
        private final int delta;

        PortMap(int delta) {
            this.delta = delta;
        }

        @Override
        protected void onEvent1(Integer e) {
            out.dispatch(e + delta);
        }
    }

    static class Join<A, B> extends Node2<A, B> {
        final List<String> triggers = new ArrayList<>();
        A a;
        B b;

        @Override protected void onEvent1(A e) { a = e; }
        @Override protected void onEvent2(B e) { b = e; }

        @Override
        protected boolean onTrigger() {
            triggers.add(a + "/" + b);
            return true;
        }
    }

    @Test
    void diamondThroughOutputPortsFiresOnceWithConsistentState() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        PortMap a = new PortMap(1);
        PortMap b = new PortMap(2);
        Join<Integer, Integer> join = new Join<>();
        a.subscribe1(tick);
        b.subscribe1(tick);
        join.subscribe1(a.out).subscribe2(b.out);
        join.start();
        a.start();
        b.start();

        tick.dispatch(100);
        tick.dispatch(200);

        assertEquals(List.of("101/102", "201/202"), join.triggers);
        assertTrue(join.rank() > a.rank());
        assertTrue(join.rank() > b.rank());
    }

    @Test
    void nodeWithTwoOutputTypesFeedsJoinOncePerTick() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        Splitter split = new Splitter();
        Join<Integer, String> join = new Join<>();
        split.subscribe1(tick).start();
        join.subscribe1(split.numbers).subscribe2(split.labels).start();

        tick.dispatch(1);
        tick.dispatch(2);

        assertEquals(List.of("2/L1", "3/L2"), join.triggers);
    }

    @Test
    void childRanksAboveOwnerWhenStartedFirst() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        PortMap a = new PortMap(1);
        PortMap b = new PortMap(1);
        PortMap c = new PortMap(1);
        c.subscribe1(b.out).start();
        b.subscribe1(a.out).start();
        a.subscribe1(tick).start();

        assertTrue(b.rank() > a.rank());
        assertTrue(c.rank() > b.rank());
    }

    @Test
    void triggerReturningFalseDropsPortOutput() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        Splitter split = new Splitter() {
            @Override
            protected boolean onTrigger() {
                return false;
            }
        };
        Join<Integer, String> join = new Join<>();
        split.subscribe1(tick).start();
        join.subscribe1(split.numbers).subscribe2(split.labels).start();

        tick.dispatch(1);

        assertTrue(join.triggers.isEmpty());
    }

    @Test
    void skipEventDiscardsPortOutputOfFailingHandler() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        PortMap a = new PortMap(1) {
            @Override
            protected void onEvent1(Integer e) {
                super.onEvent1(e);
                if (e == 1) throw new IllegalStateException("boom");
            }
        };
        a.errorPolicy(ErrorPolicy.SKIP_EVENT);
        List<Integer> seen = new ArrayList<>();
        Node1<Integer> sink = new Node1<>() {
            @Override protected void onEvent1(Integer e) { seen.add(e); }
        };
        a.subscribe1(tick).start();
        sink.subscribe1(a.out).start();

        tick.dispatch(1);
        tick.dispatch(2);

        assertEquals(List.of(3), seen);
    }

    @Test
    void portCanBeDrivenFromOutsideACycle() {
        PortMap a = new PortMap(0);
        List<Integer> seen = new ArrayList<>();
        a.out.register(seen::add);

        a.out.dispatch(7);

        assertEquals(List.of(7), seen);
        assertSame(a, a.out.owner());
    }

    @Test
    void stoppingOwnerKeepsChildRunnableAfterRestart() {
        EventDispatcher<Integer> tick = EventDispatcher.create();
        PortMap a = new PortMap(1);
        List<Integer> seen = new ArrayList<>();
        Node1<Integer> sink = new Node1<>() {
            @Override protected void onEvent1(Integer e) { seen.add(e); }
        };
        a.subscribe1(tick).start();
        sink.subscribe1(a.out).start();

        a.stop();
        tick.dispatch(1);
        a.start();
        tick.dispatch(2);

        assertEquals(List.of(3), seen);
        assertTrue(sink.rank() > a.rank());
    }
}
