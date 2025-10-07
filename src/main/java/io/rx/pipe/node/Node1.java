package io.rx.pipe.node;

import io.rx.pipe.dispatcher.Dispatcher;

public abstract class Node1<E1> implements Node {
    protected Dispatcher<E1> dispatcher1;
    
    public abstract void onEvent1(E1 event);

    public Node1<E1> subscribe1(Dispatcher<E1> dispatcher1) {
        this.dispatcher1 = dispatcher1;
        return this;
    }

    public void start() {
        this.dispatcher1.register(this::onEvent1);
    }
}