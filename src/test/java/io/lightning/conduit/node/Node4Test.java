package io.lightning.conduit.node;

import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node4Test {

    private Dispatcher<String>  d1;
    private Dispatcher<Integer> d2;
    private Dispatcher<Long>    d3;
    private Dispatcher<Double>  d4;

    @BeforeEach
    void setUp() {
        d1 = EventDispatcher.create();
        d2 = EventDispatcher.create();
        d3 = EventDispatcher.create();
        d4 = EventDispatcher.create();
    }

    private static class RecordingNode extends Node4<String, Integer, Long, Double> {
        final List<String> log = new ArrayList<>();
        @Override protected void onEvent1(String e)  { log.add("1:" + e); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); }
        @Override protected void onEvent4(Double e)  { log.add("4:" + e); }
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).start();

        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0);

        assertEquals(List.of("1:a", "2:2", "3:3", "4:4.0"), node.log);
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() {
        for (int target = 1; target <= 4; target++) {
            Dispatcher<String>  l1 = EventDispatcher.create();
            Dispatcher<Integer> l2 = EventDispatcher.create();
            Dispatcher<Long>    l3 = EventDispatcher.create();
            Dispatcher<Double>  l4 = EventDispatcher.create();

            List<Integer> hitSlots = new ArrayList<>();
            final int t = target;
            Node4<String, Integer, Long, Double> node = new Node4<>() {
                @Override protected void onEvent1(String v)  { if (t == 1) hitSlots.add(1); else fail("unexpected onEvent1"); }
                @Override protected void onEvent2(Integer v) { if (t == 2) hitSlots.add(2); else fail("unexpected onEvent2"); }
                @Override protected void onEvent3(Long v)    { if (t == 3) hitSlots.add(3); else fail("unexpected onEvent3"); }
                @Override protected void onEvent4(Double v)  { if (t == 4) hitSlots.add(4); else fail("unexpected onEvent4"); }
            };
            node.subscribe1(l1).subscribe2(l2).subscribe3(l3).subscribe4(l4).start();

            if (t == 1) l1.dispatch("x");
            if (t == 2) l2.dispatch(0);
            if (t == 3) l3.dispatch(0L);
            if (t == 4) l4.dispatch(0.0);

            assertEquals(List.of(t), hitSlots, "dispatcher " + t + " should only trigger onEvent" + t);
        }
    }

    @Test
    void testOrderPreservedWithinEachDispatcher() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).start();

        d1.dispatch("a"); d1.dispatch("b");
        d2.dispatch(1);   d2.dispatch(2);
        d3.dispatch(10L); d3.dispatch(20L);
        d4.dispatch(1.1); d4.dispatch(2.2);

        assertEquals(List.of("1:a","1:b","2:1","2:2","3:10","3:20","4:1.1","4:2.2"), node.log);
    }

    @Test
    void testNoEventsBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4);

        d1.dispatch("x"); d2.dispatch(0); d3.dispatch(0L); d4.dispatch(0.0);
        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithMissingSubscriptionThrowsNPE() {
        Node4<String, Integer, Long, Double> node = new Node4<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
            @Override protected void onEvent4(Double v)  {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testExceptionIsolation() {
        RecordingNode node = new RecordingNode() {
            @Override protected void onEvent3(Long e) {
                if (e == -1L) throw new RuntimeException("intentional");
                super.onEvent3(e);
            }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).start();

        d3.dispatch(1L); d3.dispatch(-1L); d3.dispatch(2L);
        d1.dispatch("ok"); d4.dispatch(9.9);

        assertTrue(node.log.contains("3:1"));
        assertTrue(node.log.contains("3:2"));
        assertFalse(node.log.contains("3:-1"));
        assertTrue(node.log.contains("1:ok"));
        assertTrue(node.log.contains("4:9.9"));
    }

    @Test
    void testMultipleNodesOnSameDispatchers() {
        RecordingNode nodeA = new RecordingNode();
        RecordingNode nodeB = new RecordingNode();
        nodeA.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).start();
        nodeB.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).start();

        d1.dispatch("x"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0);

        assertEquals(List.of("1:x","2:2","3:3","4:4.0"), nodeA.log);
        assertEquals(List.of("1:x","2:2","3:3","4:4.0"), nodeB.log);
    }
}
