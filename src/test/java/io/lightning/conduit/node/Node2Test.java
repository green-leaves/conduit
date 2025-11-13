package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
public class Node2Test {

    private TestNode2 node;
    private Dispatcher<String> dispatcher1;
    private Dispatcher<Integer> dispatcher2;

    @BeforeEach
    void setUp() {
        node = new TestNode2();
        dispatcher1 = EventDispatcher.create();
        dispatcher2 = EventDispatcher.create();
    }

    @Test
    void testNode2() {
        node
            .subscribe1(dispatcher1)
            .subscribe2(dispatcher2)
            .start();

        Node1<String> consumeNode = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                log.info("consume event={}", event);
            }
        };

        consumeNode
                .subscribe1(node.getOutputDispatcher())
                .start();

        // Dispatch events
        dispatcher1.dispatch("event1");
        dispatcher2.dispatch(42);
        dispatcher1.dispatch("event2");
        dispatcher2.dispatch(99);

        // Verify the events were processed in order by the node
        String eventOrder = node.getEventOrder();
        assertEquals("1:event1;2:42;1:event2;2:99;", eventOrder,
            "Events should be processed in the order they were dispatched");
    }

    /**
     * Concrete implementation of Node2 for testing purposes
     */
    private static class TestNode2 extends Node2<String, Integer> {
        private final StringBuilder eventOrder = new StringBuilder();

        @Getter
        private final Dispatcher<String> outputDispatcher = EventDispatcher.create();

        @Override
        protected void onEvent1(String event) {
            eventOrder.append("1:").append(event).append(";");
            outputDispatcher.dispatch(eventOrder.toString());
        }

        @Override
        protected void onEvent2(Integer event) {
            eventOrder.append("2:").append(event).append(";");
            outputDispatcher.dispatch(eventOrder.toString());
        }

        public String getEventOrder() {
            return eventOrder.toString();
        }
    }
}