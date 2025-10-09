package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;

public abstract class Node2<E1, E2> implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);

    public Node2<E1, E2> subscribe1(Dispatcher<E1> dispatcher1) {
        this.dispatcher1 = dispatcher1;
        return this;
    }
    public Node2<E1, E2> subscribe2(Dispatcher<E2> dispatcher2) {
        this.dispatcher2 = dispatcher2;
        return this;
    }

    public void start() {
        this.dispatcher1.register(this::onEvent1);
        this.dispatcher2.register(this::onEvent2);
    }

}
