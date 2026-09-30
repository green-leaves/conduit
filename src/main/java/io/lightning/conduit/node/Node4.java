package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;

public abstract class Node4<E1, E2, E3, E4> extends GraphNode implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;
    protected Dispatcher<E4> dispatcher4;

    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);
    protected abstract void onEvent3(E3 event);
    protected abstract void onEvent4(E4 event);

    public Node4<E1, E2, E3, E4> subscribe1(Dispatcher<E1> dispatcher1) {
        checkNotStarted();
        this.dispatcher1 = dispatcher1;
        return this;
    }

    public Node4<E1, E2, E3, E4> subscribe2(Dispatcher<E2> dispatcher2) {
        checkNotStarted();
        this.dispatcher2 = dispatcher2;
        return this;
    }

    public Node4<E1, E2, E3, E4> subscribe3(Dispatcher<E3> dispatcher3) {
        checkNotStarted();
        this.dispatcher3 = dispatcher3;
        return this;
    }

    public Node4<E1, E2, E3, E4> subscribe4(Dispatcher<E4> dispatcher4) {
        checkNotStarted();
        this.dispatcher4 = dispatcher4;
        return this;
    }

    @Override
    protected final Dispatcher<?>[] inputs() {
        return new Dispatcher<?>[]{dispatcher1, dispatcher2, dispatcher3, dispatcher4};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void deliver(int input, Object event) {
        switch (input) {
            case 1 -> onEvent1((E1) event);
            case 2 -> onEvent2((E2) event);
            case 3 -> onEvent3((E3) event);
            case 4 -> onEvent4((E4) event);
            default -> throw new IllegalArgumentException("no input " + input);
        }
    }
}
