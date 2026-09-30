package io.lightning.conduit.dispatcher;

import java.util.List;

/**
 * A {@link Dispatcher} backed by a {@link ListenerRegistry} and driven by the single-threaded
 * cycle engine. Nodes that also produce output implement this interface and hold a registry:
 *
 * <pre>{@code
 * class Normalizer extends Node1<Tick> implements EventDispatcher<Rate> {
 *     private final ListenerRegistry<Rate> listeners = new ListenerRegistry<>();
 *     public ListenerRegistry<Rate> listeners() { return listeners; }
 *     protected void onEvent1(Tick t) { dispatch(toRate(t)); }
 * }
 * }</pre>
 *
 * <p>An external {@code dispatch} runs one complete cycle (and any follow-up cycles caused by
 * re-entrant dispatches) before returning. Dispatches made from inside a handler never recurse:
 * output of a node to its own children is propagated within the same cycle in topological
 * order; anything else is queued as a follow-up cycle.
 */
public interface EventDispatcher<E> extends Dispatcher<E> {

    /** The listener storage backing this dispatcher. */
    ListenerRegistry<E> listeners();

    /** Read-only snapshot of the registered listeners. */
    default List<Listener<E>> getListeners() {
        return listeners().asList();
    }

    @Override
    default void register(Listener<E> listener) {
        listeners().add(listener);
    }

    @Override
    default boolean unregister(Listener<E> listener) {
        return listeners().remove(listener);
    }

    @Override
    default void clear() {
        listeners().clear();
    }

    @Override
    default void dispatch(E event) {
        CycleEngine.dispatch(this, listeners().snapshot(), event);
    }

    /**
     * Static factory method to create an EventDispatcher instance.
     */
    static <E> EventDispatcher<E> create() {
        return new DefaultEventDispatcher<>();
    }
}
