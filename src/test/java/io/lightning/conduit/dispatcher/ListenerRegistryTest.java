package io.lightning.conduit.dispatcher;

import io.lightning.conduit.dispatcher.Dispatcher.Listener;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ListenerRegistryTest {

    @Test
    void addAppendsInRegistrationOrder() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        Listener<String> a = e -> { };
        Listener<String> b = e -> { };
        assertTrue(r.add(a));
        assertTrue(r.add(b));
        assertEquals(List.of(a, b), r.asList());
        assertEquals(2, r.size());
        assertFalse(r.isEmpty());
    }

    @Test
    void duplicateInstanceIsIgnored() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        Listener<String> a = e -> { };
        assertTrue(r.add(a));
        assertFalse(r.add(a));
        assertEquals(1, r.size());
    }

    @Test
    void nullListenerIsRejected() {
        assertThrows(NullPointerException.class, () -> new ListenerRegistry<String>().add(null));
    }

    @Test
    void removeFirstMiddleLastAndMissing() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        Listener<String> a = e -> { }, b = e -> { }, c = e -> { }, d = e -> { };
        r.add(a); r.add(b); r.add(c); r.add(d);
        assertTrue(r.remove(b));
        assertEquals(List.of(a, c, d), r.asList());
        assertTrue(r.remove(a));
        assertTrue(r.remove(d));
        assertEquals(List.of(c), r.asList());
        assertFalse(r.remove(a));
        assertTrue(r.remove(c));
        assertTrue(r.isEmpty());
    }

    @Test
    void clearRemovesEverything() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        r.add(e -> { });
        r.add(e -> { });
        r.clear();
        assertEquals(0, r.size());
        assertTrue(r.asList().isEmpty());
    }

    @Test
    void asListIsReadOnlyAndDetached() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        Listener<String> a = e -> { };
        r.add(a);
        List<Listener<String>> view = r.asList();
        assertThrows(UnsupportedOperationException.class, () -> view.add(e -> { }));
        r.add(e -> { });
        assertEquals(1, view.size(), "snapshot must not change after later registrations");
    }

    @Test
    void snapshotIsNotMutatedByLaterChanges() {
        ListenerRegistry<String> r = new ListenerRegistry<>();
        Listener<String> a = e -> { };
        r.add(a);
        Listener<?>[] before = r.snapshot();
        r.add(e -> { });
        r.remove(a);
        assertEquals(1, before.length);
        assertSame(a, before[0]);
    }

    @Test
    void concurrentAddsAreAllKept() throws InterruptedException {
        ListenerRegistry<Integer> r = new ListenerRegistry<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(new Thread(() -> {
                for (int i = 0; i < 250; i++) {
                    int id = i;
                    r.add(e -> Integer.hashCode(id)); // capturing lambda: a distinct instance each time
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();
        assertEquals(1000, r.size());
    }
}
