package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;

public abstract class Node3<E1, E2, E3> implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;
    
    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);
    protected abstract void onEvent3(E3 event);

    public Node3<E1, E2, E3> subscribe1(Dispatcher<E1> dispatcher1) {
        this.dispatcher1 = dispatcher1;
        return this;
    }
    
    public Node3<E1, E2, E3> subscribe2(Dispatcher<E2> dispatcher2) {
        this.dispatcher2 = dispatcher2;
        return this;
    }
    
    public Node3<E1, E2, E3> subscribe3(Dispatcher<E3> dispatcher3) {
        this.dispatcher3 = dispatcher3;
        return this;
    }

    public void start() {
        this.dispatcher1.register(this::onEvent1);
        this.dispatcher2.register(this::onEvent2);
        this.dispatcher3.register(this::onEvent3);
    }
}