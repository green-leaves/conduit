package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node2Test {

    private Dispatcher<String> dispatcher1;
    private Dispatcher<Integer> dispatcher2;

    @BeforeEach
    void setUp() {
        dispatcher1 = EventDispatcher.create();
        dispatcher2 = EventDispatcher.create();
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /** Records every event as "1:<value>" or "2:<value>" in arrival order. */
    private static class RecordingNode extends Node2<String, Integer> {
        final List<String> log = new ArrayList<>();

        @Override
        protected void onEvent1(String event) { log.add("1:" + event); }

        @Override
        protected void onEvent2(Integer event) { log.add("2:" + event); }
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    void testEachDispatcherRoutesToCorrectHandler() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("hello");
        dispatcher2.dispatch(42);

        assertEquals(List.of("1:hello", "2:42"), node.log);
    }

    @Test
    void testMultipleEventsFromEachDispatcherInOrder() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("a");
        dispatcher1.dispatch("b");
        dispatcher2.dispatch(1);
        dispatcher2.dispatch(2);

        assertEquals(List.of("1:a", "1:b", "2:1", "2:2"), node.log);
    }

    @Test
    void testInterleavedEventsPreserveOrder() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("x");
        dispatcher2.dispatch(10);
        dispatcher1.dispatch("y");
        dispatcher2.dispatch(20);

        assertEquals(List.of("1:x", "2:10", "1:y", "2:20"), node.log);
    }

    @Test
    void testDispatcher1EventsOnlyGoToOnEvent1() {
        List<String> onEvent1Calls = new ArrayList<>();
        List<Integer> onEvent2Calls = new ArrayList<>();

        Node2<String, Integer> node = new Node2<>() {
            @Override protected void onEvent1(String e) { onEvent1Calls.add(e); }
            @Override protected void onEvent2(Integer e) { onEvent2Calls.add(e); }
        };

        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("only-one");

        assertEquals(List.of("only-one"), onEvent1Calls);
        assertTrue(onEvent2Calls.isEmpty());
    }

    @Test
    void testDispatcher2EventsOnlyGoToOnEvent2() {
        List<String> onEvent1Calls = new ArrayList<>();
        List<Integer> onEvent2Calls = new ArrayList<>();

        Node2<String, Integer> node = new Node2<>() {
            @Override protected void onEvent1(String e) { onEvent1Calls.add(e); }
            @Override protected void onEvent2(Integer e) { onEvent2Calls.add(e); }
        };

        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher2.dispatch(99);

        assertTrue(onEvent1Calls.isEmpty());
        assertEquals(List.of(99), onEvent2Calls);
    }

    @Test
    void testNoEventsDeliveredBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(dispatcher1).subscribe2(dispatcher2);

        dispatcher1.dispatch("ignored");
        dispatcher2.dispatch(0);

        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithoutSubscribe1ThrowsNullPointerException() {
        Node2<String, Integer> node = new Node2<>() {
            @Override protected void onEvent1(String e) {}
            @Override protected void onEvent2(Integer e) {}
        };
        node.subscribe2(dispatcher2);

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testStartWithoutSubscribe2ThrowsNullPointerException() {
        Node2<String, Integer> node = new Node2<>() {
            @Override protected void onEvent1(String e) {}
            @Override protected void onEvent2(Integer e) {}
        };
        node.subscribe1(dispatcher1);

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testExceptionInOnEvent1DoesNotPreventSubsequentEvents() {
        RecordingNode node = new RecordingNode() {
            @Override
            protected void onEvent1(String event) {
                if ("bad".equals(event)) throw new RuntimeException("intentional");
                super.onEvent1(event);
            }
        };
        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("good1");
        dispatcher1.dispatch("bad");
        dispatcher1.dispatch("good2");

        assertEquals(List.of("1:good1", "1:good2"), node.log);
    }

    @Test
    void testExceptionInOnEvent2DoesNotPreventSubsequentEvents() {
        RecordingNode node = new RecordingNode() {
            @Override
            protected void onEvent2(Integer event) {
                if (event == -1) throw new RuntimeException("intentional");
                super.onEvent2(event);
            }
        };
        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher2.dispatch(1);
        dispatcher2.dispatch(-1);
        dispatcher2.dispatch(2);

        assertEquals(List.of("2:1", "2:2"), node.log);
    }

    @Test
    void testMultipleNodesCanSubscribeToSameDispatchers() {
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();

        nodeA.subscribe1(dispatcher1).subscribe2(dispatcher2).start();
        nodeB.subscribe1(dispatcher1).subscribe2(dispatcher2).start();

        dispatcher1.dispatch("broadcast");
        dispatcher2.dispatch(7);

        assertEquals(List.of("1:broadcast", "2:7"), nodeA.log);
        assertEquals(List.of("1:broadcast", "2:7"), nodeB.log);
    }

    @Test
    void testNodeCanDispatchToDownstream() {
        EventDispatcher<String> outputDispatcher = EventDispatcher.create();
        List<String> downstream = new ArrayList<>();

        Node2<String, Integer> node = new Node2<>() {
            @Override
            protected void onEvent1(String event) { outputDispatcher.dispatch("str:" + event); }
            @Override
            protected void onEvent2(Integer event) { outputDispatcher.dispatch("int:" + event); }
        };

        Node1<String> consumer = new Node1<>() {
            @Override
            protected void onEvent1(String event) { downstream.add(event); }
        };

        node.subscribe1(dispatcher1).subscribe2(dispatcher2).start();
        consumer.subscribe1(outputDispatcher).start();

        dispatcher1.dispatch("hello");
        dispatcher2.dispatch(5);

        assertEquals(List.of("str:hello", "int:5"), downstream);
    }
}
