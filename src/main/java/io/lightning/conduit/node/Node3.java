package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;

public abstract class Node3<E1, E2, E3> extends GraphNode implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;

    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);
    protected abstract void onEvent3(E3 event);

    public Node3<E1, E2, E3> subscribe1(Dispatcher<E1> dispatcher1) {
        checkNotStarted();
        this.dispatcher1 = dispatcher1;
        return this;
    }

    public Node3<E1, E2, E3> subscribe2(Dispatcher<E2> dispatcher2) {
        checkNotStarted();
        this.dispatcher2 = dispatcher2;
        return this;
    }

    public Node3<E1, E2, E3> subscribe3(Dispatcher<E3> dispatcher3) {
        checkNotStarted();
        this.dispatcher3 = dispatcher3;
        return this;
    }

    @Override
    protected final Dispatcher<?>[] inputs() {
        return new Dispatcher<?>[]{dispatcher1, dispatcher2, dispatcher3};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void deliver(int input, Object event) {
        switch (input) {
            case 1 -> onEvent1((E1) event);
            case 2 -> onEvent2((E2) event);
            case 3 -> onEvent3((E3) event);
            default -> throw new IllegalArgumentException("no input " + input);
        }
    }
}
