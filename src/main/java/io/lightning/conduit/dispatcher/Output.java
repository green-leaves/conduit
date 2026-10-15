package io.lightning.conduit.dispatcher;

import java.util.Objects;

/**
 * An output port owned by a node. Create one per output with {@link GraphNode#output()}:
 *
 * <pre>{@code
 * class Pricer extends Node1<Tick> {
 *     final Output<Quote> quotes = output();
 *     final Output<Hedge> hedges = output();
 *     protected void onEvent1(Tick t) { quotes.dispatch(quote(t)); hedges.dispatch(hedge(t)); }
 * }
 * pricer.subscribe1(ticks);
 * quoter.subscribe1(pricer.quotes);
 * }</pre>
 *
 * <p>For scheduling a port is the node itself: nodes subscribed to it rank above the owner, and
 * whatever the owner dispatches through it while running is staged and released to those nodes in
 * the same cycle, exactly like a node that implements {@link EventDispatcher}. This is how a node
 * gets several outputs, or outputs of several types.
 */
public final class Output<E> implements EventDispatcher<E> {
    private final GraphNode owner;
    private final ListenerRegistry<E> listeners = new ListenerRegistry<>();

    Output(GraphNode owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    /** The node this port belongs to. */
    public GraphNode owner() {
        return owner;
    }

    @Override
    public ListenerRegistry<E> listeners() {
        return listeners;
    }

    @Override
    public String toString() {
        return owner.describe() + "#output[listeners=" + listeners.size() + "]";
    }
}
