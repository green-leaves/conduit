package io.lightning.conduit.dispatcher;

import io.lightning.conduit.dispatcher.Dispatcher.Listener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EventDispatcherTest {

    @Test
    void dispatchReachesAllListenersInRegistrationOrder() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        d.register(e -> log.add("a:" + e));
        d.register(e -> log.add("b:" + e));
        d.dispatch("x");
        assertEquals(List.of("a:x", "b:x"), log);
    }

    @Test
    void dispatchWithNoListenersIsNoOp() {
        assertDoesNotThrow(() -> EventDispatcher.<String>create().dispatch("x"));
    }

    @Test
    void registeringSameListenerTwiceDeliversOnce() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        Listener<String> l = log::add;
        d.register(l);
        d.register(l);
        d.dispatch("x");
        assertEquals(List.of("x"), log);
    }

    @Test
    void unregisterStopsDelivery() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        Listener<String> l = log::add;
        d.register(l);
        assertTrue(d.unregister(l));
        assertFalse(d.unregister(l));
        d.dispatch("x");
        assertTrue(log.isEmpty());
    }

    @Test
    void clearRemovesAllListeners() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        d.register(log::add);
        d.register(log::add);
        d.clear();
        d.dispatch("x");
        assertTrue(log.isEmpty());
        assertTrue(d.getListeners().isEmpty());
    }

    @Test
    void getListenersIsReadOnly() {
        EventDispatcher<String> d = EventDispatcher.create();
        d.register(e -> { });
        assertThrows(UnsupportedOperationException.class, () -> d.getListeners().clear());
        assertEquals(1, d.getListeners().size());
    }

    @Test
    void nullListenerIsRejected() {
        assertThrows(NullPointerException.class, () -> EventDispatcher.<String>create().register(null));
    }

    @Test
    void nullEventIsDelivered() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        d.register(log::add);
        d.dispatch(null);
        assertEquals(1, log.size());
        assertNull(log.get(0));
    }

    @Test
    void listenerRegisteredDuringDispatchDoesNotSeeCurrentEvent() {
        EventDispatcher<String> d = EventDispatcher.create();
        List<String> late = new ArrayList<>();
        d.register(e -> d.register(late::add));
        d.dispatch("first");
        assertTrue(late.isEmpty());
        d.dispatch("second");
        assertEquals(List.of("second"), late);
    }

    @Test
    void reentrantDispatchFromListenerIsQueuedNotRecursive() {
        EventDispatcher<Integer> d = EventDispatcher.create();
        List<String> log = new ArrayList<>();
        d.register(e -> {
            log.add("enter " + e);
            if (e < 3) d.dispatch(e + 1);
            log.add("exit " + e);
        });
        d.dispatch(1);
        assertEquals(List.of("enter 1", "exit 1", "enter 2", "exit 2", "enter 3", "exit 3"), log);
    }

    @Test
    void customDispatcherCallingListenersDirectlyStillDrivesNodes() {
        // a Dispatcher that does not use the registry and calls listeners itself
        class Manual implements Dispatcher<String> {
            final List<Listener<String>> ls = new ArrayList<>();
            public void register(Listener<String> l) { ls.add(l); }
            public boolean unregister(Listener<String> l) { return ls.remove(l); }
            public void clear() { ls.clear(); }
            public void dispatch(String e) { for (Listener<String> l : ls) l.onEvent(e); }
        }
        Manual manual = new Manual();
        List<String> log = new ArrayList<>();
        GraphNode node = new GraphNode() {
            protected Dispatcher<?>[] inputs() { return new Dispatcher<?>[]{manual}; }
            protected void deliver(int input, Object event) { log.add(input + ":" + event); }
        };
        node.start();
        manual.dispatch("hi");
        assertEquals(List.of("1:hi"), log);
    }
}
