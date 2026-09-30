package io.lightning.conduit.dispatcher;

import io.lightning.conduit.dispatcher.Dispatcher.Listener;

import java.util.List;
import java.util.Objects;

/**
 * Copy-on-write listener storage. Mutations are rare and synchronized; dispatch reads a single
 * volatile array snapshot, so the hot path neither locks nor allocates. Duplicate registrations
 * of the same instance are ignored.
 */
public final class ListenerRegistry<E> {
    private static final Listener<?>[] EMPTY = new Listener<?>[0];

    private volatile Listener<?>[] listeners = EMPTY;

    /** @return {@code true} if added, {@code false} if this instance was already registered */
    public synchronized boolean add(Listener<E> listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        Listener<?>[] current = listeners;
        for (Listener<?> l : current) {
            if (l == listener) return false;
        }
        Listener<?>[] next = new Listener<?>[current.length + 1];
        System.arraycopy(current, 0, next, 0, current.length);
        next[current.length] = listener;
        listeners = next;
        return true;
    }

    public synchronized boolean remove(Listener<?> listener) {
        Listener<?>[] current = listeners;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == listener) {
                if (current.length == 1) {
                    listeners = EMPTY;
                } else {
                    Listener<?>[] next = new Listener<?>[current.length - 1];
                    System.arraycopy(current, 0, next, 0, i);
                    System.arraycopy(current, i + 1, next, i, current.length - i - 1);
                    listeners = next;
                }
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        listeners = EMPTY;
    }

    public int size() {
        return listeners.length;
    }

    public boolean isEmpty() {
        return listeners.length == 0;
    }

    /** Immutable point-in-time view, for inspection only. */
    @SuppressWarnings("unchecked")
    public List<Listener<E>> asList() {
        return (List<Listener<E>>) (List<?>) List.of(listeners);
    }

    /** The live snapshot array; shared, never mutated in place, must not be modified by callers. */
    Listener<?>[] snapshot() {
        return listeners;
    }
}
