package io.lightning.conduit.node;

import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node3Test {

    private Dispatcher<String>  d1;
    private Dispatcher<Integer> d2;
    private Dispatcher<Long>    d3;

    @BeforeEach
    void setUp() {
        d1 = EventDispatcher.create();
        d2 = EventDispatcher.create();
        d3 = EventDispatcher.create();
    }

    private static class RecordingNode extends Node3<String, Integer, Long> {
        final List<String> log = new ArrayList<>();
        @Override protected void onEvent1(String e)  { log.add("1:" + e); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); }
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("alpha");
        d2.dispatch(2);
        d3.dispatch(3L);

        assertEquals(List.of("1:alpha", "2:2", "3:3"), node.log);
    }

    @Test
    void testDispatcher1DoesNotTriggerOtherHandlers() {
        List<String>  e1 = new ArrayList<>();
        List<Integer> e2 = new ArrayList<>();
        List<Long>    e3 = new ArrayList<>();
        Node3<String, Integer, Long> node = new Node3<>() {
            @Override protected void onEvent1(String v)  { e1.add(v); }
            @Override protected void onEvent2(Integer v) { e2.add(v); }
            @Override protected void onEvent3(Long v)    { e3.add(v); }
        };
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("only");
        assertEquals(List.of("only"), e1);
        assertTrue(e2.isEmpty());
        assertTrue(e3.isEmpty());
    }

    @Test
    void testDispatcher2DoesNotTriggerOtherHandlers() {
        List<String>  e1 = new ArrayList<>();
        List<Integer> e2 = new ArrayList<>();
        List<Long>    e3 = new ArrayList<>();
        Node3<String, Integer, Long> node = new Node3<>() {
            @Override protected void onEvent1(String v)  { e1.add(v); }
            @Override protected void onEvent2(Integer v) { e2.add(v); }
            @Override protected void onEvent3(Long v)    { e3.add(v); }
        };
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d2.dispatch(99);
        assertTrue(e1.isEmpty());
        assertEquals(List.of(99), e2);
        assertTrue(e3.isEmpty());
    }

    @Test
    void testDispatcher3DoesNotTriggerOtherHandlers() {
        List<String>  e1 = new ArrayList<>();
        List<Integer> e2 = new ArrayList<>();
        List<Long>    e3 = new ArrayList<>();
        Node3<String, Integer, Long> node = new Node3<>() {
            @Override protected void onEvent1(String v)  { e1.add(v); }
            @Override protected void onEvent2(Integer v) { e2.add(v); }
            @Override protected void onEvent3(Long v)    { e3.add(v); }
        };
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d3.dispatch(77L);
        assertTrue(e1.isEmpty());
        assertTrue(e2.isEmpty());
        assertEquals(List.of(77L), e3);
    }

    @Test
    void testOrderPreservedWithinEachDispatcher() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("a"); d1.dispatch("b");
        d2.dispatch(1);   d2.dispatch(2);
        d3.dispatch(10L); d3.dispatch(20L);

        assertEquals(List.of("1:a", "1:b", "2:1", "2:2", "3:10", "3:20"), node.log);
    }

    @Test
    void testNoEventsBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3);

        d1.dispatch("ignored"); d2.dispatch(0); d3.dispatch(0L);
        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithMissingSubscriptionThrowsNPE() {
        Node3<String, Integer, Long> node = new Node3<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testExceptionInOneHandlerDoesNotAffectOthers() {
        RecordingNode node = new RecordingNode() {
            @Override protected void onEvent2(Integer e) {
                if (e == -1) throw new RuntimeException("intentional");
                super.onEvent2(e);
            }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d2.dispatch(1); d2.dispatch(-1); d2.dispatch(2);
        d1.dispatch("ok");
        d3.dispatch(5L);

        assertTrue(node.log.contains("2:1"));
        assertTrue(node.log.contains("2:2"));
        assertFalse(node.log.contains("2:-1"));
        assertTrue(node.log.contains("1:ok"));
        assertTrue(node.log.contains("3:5"));
    }

    @Test
    void testMultipleNodesOnSameDispatchers() {
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        nodeA.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();
        nodeB.subscribe1(d1).subscribe2(d2).subscribe3(d3).start();

        d1.dispatch("x"); d2.dispatch(7); d3.dispatch(3L);

        assertEquals(List.of("1:x", "2:7", "3:3"), nodeA.log);
        assertEquals(List.of("1:x", "2:7", "3:3"), nodeB.log);
    }
}
