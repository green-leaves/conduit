package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.EventDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class Node1Test {

    private Dispatcher<String> dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = EventDispatcher.create();
    }

    @Test
    void testEventIsDeliveredToOnEvent1() {
        List<String> received = new ArrayList<>();
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                received.add(event);
            }
        };

        node.subscribe1(dispatcher).start();
        dispatcher.dispatch("hello");

        assertEquals(List.of("hello"), received);
    }

    @Test
    void testMultipleEventsReceivedInOrder() {
        List<String> received = new ArrayList<>();
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                received.add(event);
            }
        };

        node.subscribe1(dispatcher).start();
        dispatcher.dispatch("a");
        dispatcher.dispatch("b");
        dispatcher.dispatch("c");

        assertEquals(List.of("a", "b", "c"), received);
    }

    @Test
    void testNullEventIsDelivered() {
        List<String> received = new ArrayList<>();
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                received.add(event);
            }
        };

        node.subscribe1(dispatcher).start();
        dispatcher.dispatch(null);

        assertEquals(1, received.size());
        assertNull(received.get(0));
    }

    @Test
    void testNoEventsReceivedBeforeStart() {
        List<String> received = new ArrayList<>();
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                received.add(event);
            }
        };

        // subscribed but not started — listener not registered yet
        node.subscribe1(dispatcher);
        dispatcher.dispatch("ignored");

        assertTrue(received.isEmpty());
    }

    @Test
    void testStartWithoutSubscribeThrowsNullPointerException() {
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {}
        };

        assertThrows(NullPointerException.class, node::start);
    }

    @Test
    void testNodeCanActAsDownstreamConsumer() {
        // Node A produces output that Node B consumes
        Dispatcher<Integer> intDispatcher = EventDispatcher.create();
        List<Integer> nodeAOutput = new ArrayList<>();
        List<String> nodeBOutput = new ArrayList<>();

        // Node A receives integers and forwards them as strings via its own dispatcher
        EventDispatcher<String> nodeAOutputDispatcher = EventDispatcher.create();
        Node1<Integer> nodeA = new Node1<>() {
            @Override
            protected void onEvent1(Integer event) {
                nodeAOutput.add(event);
                nodeAOutputDispatcher.dispatch("processed:" + event);
            }
        };

        Node1<String> nodeB = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                nodeBOutput.add(event);
            }
        };

        nodeA.subscribe1(intDispatcher).start();
        nodeB.subscribe1(nodeAOutputDispatcher).start();

        intDispatcher.dispatch(1);
        intDispatcher.dispatch(2);

        assertEquals(List.of(1, 2), nodeAOutput);
        assertEquals(List.of("processed:1", "processed:2"), nodeBOutput);
    }

    @Test
    void testExceptionInOnEvent1DoesNotPreventSubsequentEvents() {
        List<String> received = new ArrayList<>();
        Node1<String> node = new Node1<>() {
            @Override
            protected void onEvent1(String event) {
                if ("bad".equals(event)) throw new RuntimeException("intentional");
                received.add(event);
            }
        };

        node.subscribe1(dispatcher).start();
        dispatcher.dispatch("good1");
        dispatcher.dispatch("bad");
        dispatcher.dispatch("good2");

        // EventDispatcher catches and logs listener exceptions — pipeline must continue
        assertEquals(List.of("good1", "good2"), received);
    }

    @Test
    void testMultipleNodesCanSubscribeToSameDispatcher() {
        List<String> received1 = new ArrayList<>();
        List<String> received2 = new ArrayList<>();

        Node1<String> node1 = new Node1<>() {
            @Override
            protected void onEvent1(String event) { received1.add(event); }
        };
        Node1<String> node2 = new Node1<>() {
            @Override
            protected void onEvent1(String event) { received2.add(event); }
        };

        node1.subscribe1(dispatcher).start();
        node2.subscribe1(dispatcher).start();

        dispatcher.dispatch("broadcast");

        assertEquals(List.of("broadcast"), received1);
        assertEquals(List.of("broadcast"), received2);
    }
}
