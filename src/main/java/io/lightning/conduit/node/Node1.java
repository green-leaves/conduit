package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;

public abstract class Node1<E1> extends GraphNode implements Node {
    protected Dispatcher<E1> dispatcher1;

    protected abstract void onEvent1(E1 event);

    public Node1<E1> subscribe1(Dispatcher<E1> dispatcher1) {
        checkNotStarted();
        this.dispatcher1 = dispatcher1;
        return this;
    }

    @Override
    protected final Dispatcher<?>[] inputs() {
        return new Dispatcher<?>[]{dispatcher1};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void deliver(int input, Object event) {
        onEvent1((E1) event);
    }
}
