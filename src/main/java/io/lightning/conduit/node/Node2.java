package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;

public abstract class Node2<E1, E2> extends GraphNode implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;

    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);

    public Node2<E1, E2> subscribe1(Dispatcher<E1> dispatcher1) {
        checkNotStarted();
        this.dispatcher1 = dispatcher1;
        return this;
    }

    public Node2<E1, E2> subscribe2(Dispatcher<E2> dispatcher2) {
        checkNotStarted();
        this.dispatcher2 = dispatcher2;
        return this;
    }

    @Override
    protected final Dispatcher<?>[] inputs() {
        return new Dispatcher<?>[]{dispatcher1, dispatcher2};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void deliver(int input, Object event) {
        switch (input) {
            case 1 -> onEvent1((E1) event);
            case 2 -> onEvent2((E2) event);
            default -> throw new IllegalArgumentException("no input " + input);
        }
    }
}
