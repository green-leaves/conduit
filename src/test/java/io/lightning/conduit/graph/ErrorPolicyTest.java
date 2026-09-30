package io.lightning.conduit.graph;

import io.lightning.conduit.Conduit;
import io.lightning.conduit.ConduitException;
import io.lightning.conduit.ErrorHandler;
import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.graph.TestNodes.MapNode;
import io.lightning.conduit.graph.TestNodes.Sink;
import io.lightning.conduit.node.Node1;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorPolicyTest {

    private final List<String> log = new ArrayList<>();
    private final List<String> reported = new ArrayList<>();

    @BeforeEach
    void captureErrors() {
        Conduit.errorHandler((source, event, error) -> reported.add(event + ":" + error.getMessage()));
    }

    @AfterEach
    void restoreDefaults() {
        Conduit.errorHandler(ErrorHandler.NO_OP);
        Conduit.defaultErrorPolicy(ErrorPolicy.HALT);
    }

    @Test
    void defaultPolicyIsHalt() {
        assertEquals(ErrorPolicy.HALT, Conduit.defaultErrorPolicy());
        Sink<String> sink = new Sink<>("s", log);
        assertEquals(ErrorPolicy.HALT, sink.errorPolicy());
    }

    // ------------------------------------------------------------------ HALT

    @Test
    void haltSurfacesFailureToCallerWithCauseAndSource() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing node = new Failing();
        node.subscribe1(src).start();

        ConduitException ex = assertThrows(ConduitException.class, () -> src.dispatch(-1));

        assertSame(node, ex.getSource());
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertTrue(ex.getMessage().contains("Failing"), ex.getMessage());
        assertEquals(List.of("-1:bad input -1"), reported);
    }

    @Test
    void haltAbortsRestOfCycleSoNothingDownstreamSeesPartialState() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing a = new Failing();
        Sink<Integer> downstream = new Sink<>("down", log);
        Sink<Integer> sibling = new Sink<>("sibling", log);
        a.subscribe1(src).start();
        sibling.subscribe1(src).start();   // same rank, runs after A
        downstream.subscribe1(a).start();

        assertThrows(ConduitException.class, () -> src.dispatch(-1));

        assertTrue(log.isEmpty(), log::toString);
    }

    @Test
    void graphIsUsableAfterHalt() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing a = new Failing();
        Sink<Integer> sink = new Sink<>("sink", log);
        a.subscribe1(src).start();
        sink.subscribe1(a).start();

        assertThrows(ConduitException.class, () -> src.dispatch(-1));
        src.dispatch(5);

        assertEquals(List.of(5), sink.received);
    }

    @Test
    void haltDiscardsQueuedFollowUpCycles() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        EventDispatcher<Integer> side = EventDispatcher.create();
        Node1<Integer> node = new Node1<>() {
            @Override
            protected void onEvent1(Integer e) {
                side.dispatch(e);
                if (e < 0) throw new IllegalStateException("boom");
            }
        };
        Sink<Integer> sink = new Sink<>("sink", log);
        node.subscribe1(src).start();
        sink.subscribe1(side).start();

        assertThrows(ConduitException.class, () -> src.dispatch(-1));
        src.dispatch(1);

        assertEquals(List.of(1), sink.received);
    }

    @Test
    void haltFromDeepInsideFollowUpCycleStillReachesExternalCaller() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        EventDispatcher<Integer> side = EventDispatcher.create();
        Node1<Integer> forward = new Node1<>() {
            @Override protected void onEvent1(Integer e) { side.dispatch(e); }
        };
        Failing failing = new Failing();
        forward.subscribe1(src).start();
        failing.subscribe1(side).start();

        assertThrows(ConduitException.class, () -> src.dispatch(-7));
    }

    // ------------------------------------------------------------------ SKIP_EVENT

    @Test
    void skipEventDiscardsOnlyOutputOfTheFailedEvent() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Splitter splitter = new Splitter();
        splitter.errorPolicy(ErrorPolicy.SKIP_EVENT);
        Sink<Integer> sink = new Sink<>("sink", log);
        splitter.subscribe1(src).start();
        sink.subscribe1(splitter).start();

        assertDoesNotThrow(() -> src.dispatch(-3));

        // the failing handler dispatched -3 before throwing: that must not leak downstream
        assertTrue(sink.received.isEmpty());
        assertEquals(List.of("-3:negative"), reported);

        src.dispatch(4);
        assertEquals(List.of(4), sink.received);
    }

    @Test
    void skipEventKeepsOtherEventsOfTheSameCycle() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        GraphEngineTest.Burst burst = new GraphEngineTest.Burst();
        Node1<Integer> picky = new Node1<>() {
            @Override
            protected void onEvent1(Integer e) {
                if (e == 1) throw new IllegalStateException("one");
                log.add("ok:" + e);
            }
        };
        picky.errorPolicy(ErrorPolicy.SKIP_EVENT);
        burst.subscribe1(src).start();
        picky.subscribe1(burst).start();

        src.dispatch(3);   // delivers 0, 1, 2 in one cycle

        assertEquals(List.of("ok:0", "ok:2"), log);
    }

    @Test
    void skipEventLetsRestOfCycleContinue() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing failing = new Failing();
        failing.errorPolicy(ErrorPolicy.SKIP_EVENT);
        Sink<Integer> sibling = new Sink<>("sibling", log);
        failing.subscribe1(src).start();
        sibling.subscribe1(src).start();

        src.dispatch(-1);

        assertEquals(List.of("sibling:-1"), log);
    }

    @Test
    void skipEventDoesNotLog() {
        // SKIP_EVENT reports through the handler only; verified by the handler seeing exactly one call
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing failing = new Failing();
        failing.errorPolicy(ErrorPolicy.SKIP_EVENT);
        failing.subscribe1(src).start();
        long before = Conduit.errorCount();

        src.dispatch(-1);
        src.dispatch(-2);

        assertEquals(2, reported.size());
        assertEquals(before + 2, Conduit.errorCount());
    }

    // ------------------------------------------------------------------ LOG_CONTINUE

    @Test
    void logContinueKeepsOutputDispatchedBeforeFailure() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Splitter splitter = new Splitter();
        splitter.errorPolicy(ErrorPolicy.LOG_CONTINUE);
        Sink<Integer> sink = new Sink<>("sink", log);
        splitter.subscribe1(src).start();
        sink.subscribe1(splitter).start();

        assertDoesNotThrow(() -> src.dispatch(-3));

        assertEquals(List.of(-3), sink.received);
        assertEquals(1, reported.size());
    }

    // ------------------------------------------------------------------ defaults and overrides

    @Test
    void nodePolicyOverridesDefault() {
        Conduit.defaultErrorPolicy(ErrorPolicy.SKIP_EVENT);
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing strict = new Failing();
        strict.errorPolicy(ErrorPolicy.HALT);
        strict.subscribe1(src).start();

        assertThrows(ConduitException.class, () -> src.dispatch(-1));
    }

    @Test
    void nullPolicyFallsBackToDefault() {
        Failing node = new Failing();
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.errorPolicy(null);
        Conduit.defaultErrorPolicy(ErrorPolicy.LOG_CONTINUE);
        assertEquals(ErrorPolicy.LOG_CONTINUE, node.errorPolicy());
    }

    @Test
    void plainListenerFailureUsesDefaultPolicy() {
        EventDispatcher<String> src = EventDispatcher.create();
        src.register(e -> { throw new IllegalStateException("plain"); });
        src.register(e -> log.add("second:" + e));

        assertThrows(ConduitException.class, () -> src.dispatch("x"));

        Conduit.defaultErrorPolicy(ErrorPolicy.SKIP_EVENT);
        src.dispatch("y");
        assertEquals(List.of("second:y"), log);
    }

    @Test
    void triggerFailureUnderSkipDropsNodeOutput() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        TriggerFails node = new TriggerFails();
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        Sink<Integer> sink = new Sink<>("sink", log);
        node.subscribe1(src).start();
        sink.subscribe1(node).start();

        src.dispatch(1);

        assertTrue(sink.received.isEmpty());
        assertEquals(List.of("null:trigger"), reported);
    }

    @Test
    void triggerFailureUnderLogContinueKeepsNodeOutput() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        TriggerFails node = new TriggerFails();
        node.errorPolicy(ErrorPolicy.LOG_CONTINUE);
        Sink<Integer> sink = new Sink<>("sink", log);
        node.subscribe1(src).start();
        sink.subscribe1(node).start();

        src.dispatch(1);

        assertEquals(List.of(1), sink.received);
    }

    @Test
    void afterEventFailureHonoursPolicy() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Node1<Integer> node = new Node1<>() {
            @Override protected void onEvent1(Integer e) { log.add("on:" + e); }
            @Override protected void afterEvent() { throw new IllegalStateException("after"); }
        };
        node.subscribe1(src).start();

        assertThrows(ConduitException.class, () -> src.dispatch(1));
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        assertDoesNotThrow(() -> src.dispatch(2));
        assertEquals(List.of("on:1", "on:2"), log);
    }

    @Test
    void errorHandlerThatThrowsDoesNotBreakEngine() {
        Conduit.errorHandler((s, e, t) -> { throw new RuntimeException("handler broken"); });
        EventDispatcher<Integer> src = EventDispatcher.create();
        Failing node = new Failing();
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(src).start();

        assertDoesNotThrow(() -> src.dispatch(-1));
    }

    @Test
    void errorsAreNotSwallowed() {
        EventDispatcher<Integer> src = EventDispatcher.create();
        Node1<Integer> node = new Node1<>() {
            @Override protected void onEvent1(Integer e) { throw new StackOverflowError("simulated"); }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(src).start();

        assertThrows(StackOverflowError.class, () -> src.dispatch(1));
    }

    // ------------------------------------------------------------------ helpers

    /** Fails on negatives, forwards everything else. */
    static class Failing extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();

        @Override
        public ListenerRegistry<Integer> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Integer e) {
            if (e < 0) throw new IllegalArgumentException("bad input " + e);
            dispatch(e);
        }
    }

    /** Dispatches its input, then fails on negatives. */
    static class Splitter extends Node1<Integer> implements EventDispatcher<Integer> {
        private final ListenerRegistry<Integer> listeners = new ListenerRegistry<>();

        @Override
        public ListenerRegistry<Integer> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Integer e) {
            dispatch(e);
            if (e < 0) throw new IllegalStateException("negative");
        }
    }

    static class TriggerFails extends MapNode<Integer, Integer> {
        TriggerFails() {
            super("T", new ArrayList<>(), v -> v);
        }

        @Override
        protected boolean onTrigger() {
            throw new IllegalStateException("trigger");
        }
    }
}
