package io.lightning.conduit.node;

import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node5Test {

    private Dispatcher<String>  d1;
    private Dispatcher<Integer> d2;
    private Dispatcher<Long>    d3;
    private Dispatcher<Double>  d4;
    private Dispatcher<Boolean> d5;

    @BeforeEach
    void setUp() {
        d1 = EventDispatcher.create();
        d2 = EventDispatcher.create();
        d3 = EventDispatcher.create();
        d4 = EventDispatcher.create();
        d5 = EventDispatcher.create();
    }

    private static class RecordingNode extends Node5<String, Integer, Long, Double, Boolean> {
        final List<String> log = new ArrayList<>();
        @Override protected void onEvent1(String e)  { log.add("1:" + e); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); }
        @Override protected void onEvent4(Double e)  { log.add("4:" + e); }
        @Override protected void onEvent5(Boolean e) { log.add("5:" + e); }
    }

    private RecordingNode startedNode() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();
        return node;
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() {
        RecordingNode node = startedNode();
        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0); d5.dispatch(true);
        assertEquals(List.of("1:a","2:2","3:3","4:4.0","5:true"), node.log);
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() {
        for (int target = 1; target <= 5; target++) {
            Dispatcher<String>  l1 = EventDispatcher.create();
            Dispatcher<Integer> l2 = EventDispatcher.create();
            Dispatcher<Long>    l3 = EventDispatcher.create();
            Dispatcher<Double>  l4 = EventDispatcher.create();
            Dispatcher<Boolean> l5 = EventDispatcher.create();

            List<Integer> hits = new ArrayList<>();
            final int t = target;
            Node5<String, Integer, Long, Double, Boolean> node = new Node5<>() {
                @Override protected void onEvent1(String v)  { if (t != 1) fail(); hits.add(1); }
                @Override protected void onEvent2(Integer v) { if (t != 2) fail(); hits.add(2); }
                @Override protected void onEvent3(Long v)    { if (t != 3) fail(); hits.add(3); }
                @Override protected void onEvent4(Double v)  { if (t != 4) fail(); hits.add(4); }
                @Override protected void onEvent5(Boolean v) { if (t != 5) fail(); hits.add(5); }
            };
            node.subscribe1(l1).subscribe2(l2).subscribe3(l3).subscribe4(l4).subscribe5(l5).start();

            if (t == 1) l1.dispatch("x");
            if (t == 2) l2.dispatch(0);
            if (t == 3) l3.dispatch(0L);
            if (t == 4) l4.dispatch(0.0);
            if (t == 5) l5.dispatch(false);

            assertEquals(List.of(t), hits);
        }
    }

    @Test
    void testOrderPreservedWithinEachDispatcher() {
        RecordingNode node = startedNode();
        d1.dispatch("a"); d1.dispatch("b");
        d5.dispatch(true); d5.dispatch(false);
        assertEquals(List.of("1:a","1:b","5:true","5:false"), node.log);
    }

    @Test
    void testNoEventsBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5);
        d1.dispatch("x"); d3.dispatch(0L); d5.dispatch(true);
        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithMissingSubscriptionThrowsNPE() {
        Node5<String, Integer, Long, Double, Boolean> node = new Node5<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
            @Override protected void onEvent4(Double v)  {}
            @Override protected void onEvent5(Boolean v) {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testExceptionIsolation() {
        RecordingNode node = new RecordingNode() {
            @Override protected void onEvent5(Boolean e) {
                if (!e) throw new RuntimeException("intentional");
                super.onEvent5(e);
            }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4).subscribe5(d5).start();

        d5.dispatch(true); d5.dispatch(false); d5.dispatch(true);
        d1.dispatch("ok");

        assertEquals(2, node.log.stream().filter(e -> e.startsWith("5:")).count());
        assertTrue(node.log.contains("5:true"));
        assertFalse(node.log.contains("5:false"));
        assertTrue(node.log.contains("1:ok"));
    }

    @Test
    void testMultipleNodesOnSameDispatchers() {
        RecordingNode nodeA = startedNode();
        RecordingNode nodeB = startedNode();

        d1.dispatch("x"); d5.dispatch(true);

        assertEquals(List.of("1:x","5:true"), nodeA.log);
        assertEquals(List.of("1:x","5:true"), nodeB.log);
    }
}
