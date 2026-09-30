package io.lightning.conduit.node;

import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node6Test {

    private Dispatcher<String>  d1;
    private Dispatcher<Integer> d2;
    private Dispatcher<Long>    d3;
    private Dispatcher<Double>  d4;
    private Dispatcher<Boolean> d5;
    private Dispatcher<Float>   d6;

    @BeforeEach
    void setUp() {
        d1 = EventDispatcher.create(); d2 = EventDispatcher.create();
        d3 = EventDispatcher.create(); d4 = EventDispatcher.create();
        d5 = EventDispatcher.create(); d6 = EventDispatcher.create();
    }

    private static class RecordingNode extends Node6<String, Integer, Long, Double, Boolean, Float> {
        final List<String> log = new ArrayList<>();
        @Override protected void onEvent1(String e)  { log.add("1:" + e); }
        @Override protected void onEvent2(Integer e) { log.add("2:" + e); }
        @Override protected void onEvent3(Long e)    { log.add("3:" + e); }
        @Override protected void onEvent4(Double e)  { log.add("4:" + e); }
        @Override protected void onEvent5(Boolean e) { log.add("5:" + e); }
        @Override protected void onEvent6(Float e)   { log.add("6:" + e); }
    }

    private RecordingNode startedNode() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3)
            .subscribe4(d4).subscribe5(d5).subscribe6(d6).start();
        return node;
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() {
        RecordingNode node = startedNode();
        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L);
        d4.dispatch(4.0); d5.dispatch(true); d6.dispatch(6.0f);
        assertEquals(List.of("1:a","2:2","3:3","4:4.0","5:true","6:6.0"), node.log);
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() {
        RecordingNode node = startedNode();
        d6.dispatch(1.5f);
        assertEquals(List.of("6:1.5"), node.log);
    }

    @Test
    void testOrderPreservedWithinEachDispatcher() {
        RecordingNode node = startedNode();
        d1.dispatch("a"); d1.dispatch("b");
        d6.dispatch(1.0f); d6.dispatch(2.0f);
        assertEquals(List.of("1:a","1:b","6:1.0","6:2.0"), node.log);
    }

    @Test
    void testNoEventsBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3)
            .subscribe4(d4).subscribe5(d5).subscribe6(d6);
        d1.dispatch("x"); d6.dispatch(0.0f);
        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithMissingSubscriptionThrowsNPE() {
        Node6<String, Integer, Long, Double, Boolean, Float> node = new Node6<>() {
            @Override protected void onEvent1(String v)  {}
            @Override protected void onEvent2(Integer v) {}
            @Override protected void onEvent3(Long v)    {}
            @Override protected void onEvent4(Double v)  {}
            @Override protected void onEvent5(Boolean v) {}
            @Override protected void onEvent6(Float v)   {}
        };
        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testExceptionIsolation() {
        RecordingNode node = new RecordingNode() {
            @Override protected void onEvent4(Double e) {
                if (e < 0) throw new RuntimeException("intentional");
                super.onEvent4(e);
            }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3)
            .subscribe4(d4).subscribe5(d5).subscribe6(d6).start();

        d4.dispatch(1.0); d4.dispatch(-1.0); d4.dispatch(2.0);
        d1.dispatch("ok");

        assertTrue(node.log.contains("4:1.0"));
        assertTrue(node.log.contains("4:2.0"));
        assertFalse(node.log.contains("4:-1.0"));
        assertTrue(node.log.contains("1:ok"));
    }

    @Test
    void testMultipleNodesOnSameDispatchers() {
        RecordingNode nodeA = startedNode();
        RecordingNode nodeB = startedNode();

        d3.dispatch(99L); d6.dispatch(3.3f);

        assertEquals(List.of("3:99","6:3.3"), nodeA.log);
        assertEquals(List.of("3:99","6:3.3"), nodeB.log);
    }
}
