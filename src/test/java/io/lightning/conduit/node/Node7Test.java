package io.lightning.conduit.node;

import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node7Test {

    private Dispatcher<String>    d1;
    private Dispatcher<Integer>   d2;
    private Dispatcher<Long>      d3;
    private Dispatcher<Double>    d4;
    private Dispatcher<Boolean>   d5;
    private Dispatcher<Float>     d6;
    private Dispatcher<Character> d7;

    @BeforeEach
    void setUp() {
        d1 = EventDispatcher.create(); d2 = EventDispatcher.create();
        d3 = EventDispatcher.create(); d4 = EventDispatcher.create();
        d5 = EventDispatcher.create(); d6 = EventDispatcher.create();
        d7 = EventDispatcher.create();
    }

    private static class RecordingNode extends Node7<String, Integer, Long, Double, Boolean, Float, Character> {
        final List<String> log = new ArrayList<>();
        @Override protected void onEvent1(String e)    { log.add("1:" + e); }
        @Override protected void onEvent2(Integer e)   { log.add("2:" + e); }
        @Override protected void onEvent3(Long e)      { log.add("3:" + e); }
        @Override protected void onEvent4(Double e)    { log.add("4:" + e); }
        @Override protected void onEvent5(Boolean e)   { log.add("5:" + e); }
        @Override protected void onEvent6(Float e)     { log.add("6:" + e); }
        @Override protected void onEvent7(Character e) { log.add("7:" + e); }
    }

    private RecordingNode startedNode() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();
        return node;
    }

    @Test
    void testAllHandlersReceiveFromCorrectDispatcher() {
        RecordingNode node = startedNode();
        d1.dispatch("a"); d2.dispatch(2); d3.dispatch(3L); d4.dispatch(4.0);
        d5.dispatch(true); d6.dispatch(6.0f); d7.dispatch('Z');
        assertEquals(List.of("1:a","2:2","3:3","4:4.0","5:true","6:6.0","7:Z"), node.log);
    }

    @Test
    void testEachDispatcherOnlyTriggersItsHandler() {
        RecordingNode node = startedNode();
        d7.dispatch('X');
        assertEquals(List.of("7:X"), node.log);
    }

    @Test
    void testOrderPreservedWithinEachDispatcher() {
        RecordingNode node = startedNode();
        d7.dispatch('A'); d7.dispatch('B'); d7.dispatch('C');
        assertEquals(List.of("7:A","7:B","7:C"), node.log);
    }

    @Test
    void testNoEventsBeforeStart() {
        RecordingNode node = new RecordingNode();
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7);
        d1.dispatch("x"); d7.dispatch('Q');
        assertTrue(node.log.isEmpty());
    }

    @Test
    void testStartWithMissingSubscriptionThrowsNPE() {
        Node7<String, Integer, Long, Double, Boolean, Float, Character> node = new Node7<>() {
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
    void testExceptionIsolation() {
        RecordingNode node = new RecordingNode() {
            @Override protected void onEvent7(Character e) {
                if (e == '!') throw new RuntimeException("intentional");
                super.onEvent7(e);
            }
        };
        node.errorPolicy(ErrorPolicy.SKIP_EVENT);
        node.subscribe1(d1).subscribe2(d2).subscribe3(d3).subscribe4(d4)
            .subscribe5(d5).subscribe6(d6).subscribe7(d7).start();

        d7.dispatch('A'); d7.dispatch('!'); d7.dispatch('B');
        d1.dispatch("ok");

        assertTrue(node.log.contains("7:A"));
        assertTrue(node.log.contains("7:B"));
        assertFalse(node.log.contains("7:!"));
        assertTrue(node.log.contains("1:ok"));
    }

    @Test
    void testMultipleNodesOnSameDispatchers() {
        RecordingNode nodeA = startedNode();
        RecordingNode nodeB = startedNode();

        d1.dispatch("x"); d7.dispatch('Y');

        assertEquals(List.of("1:x","7:Y"), nodeA.log);
        assertEquals(List.of("1:x","7:Y"), nodeB.log);
    }
}
