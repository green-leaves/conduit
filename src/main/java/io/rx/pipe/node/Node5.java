package io.rx.pipe.node;

import io.rx.pipe.dispatcher.Dispatcher;

public abstract class Node5<E1, E2, E3, E4, E5> implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;
    protected Dispatcher<E4> dispatcher4;
    protected Dispatcher<E5> dispatcher5;
    
    public abstract void onEvent1(E1 event);
    public abstract void onEvent2(E2 event);
    public abstract void onEvent3(E3 event);
    public abstract void onEvent4(E4 event);
    public abstract void onEvent5(E5 event);

    public Node5<E1, E2, E3, E4, E5> subscribe1(Dispatcher<E1> dispatcher1) {
        this.dispatcher1 = dispatcher1;
        return this;
    }
    
    public Node5<E1, E2, E3, E4, E5> subscribe2(Dispatcher<E2> dispatcher2) {
        this.dispatcher2 = dispatcher2;
        return this;
    }
    
    public Node5<E1, E2, E3, E4, E5> subscribe3(Dispatcher<E3> dispatcher3) {
        this.dispatcher3 = dispatcher3;
        return this;
    }
    
    public Node5<E1, E2, E3, E4, E5> subscribe4(Dispatcher<E4> dispatcher4) {
        this.dispatcher4 = dispatcher4;
        return this;
    }
    
    public Node5<E1, E2, E3, E4, E5> subscribe5(Dispatcher<E5> dispatcher5) {
        this.dispatcher5 = dispatcher5;
        return this;
    }

    public void start() {
        this.dispatcher1.register(this::onEvent1);
        this.dispatcher2.register(this::onEvent2);
        this.dispatcher3.register(this::onEvent3);
        this.dispatcher4.register(this::onEvent4);
        this.dispatcher5.register(this::onEvent5);
    }
}