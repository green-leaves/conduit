package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;

public abstract class Node6<E1, E2, E3, E4, E5, E6> implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;
    protected Dispatcher<E4> dispatcher4;
    protected Dispatcher<E5> dispatcher5;
    protected Dispatcher<E6> dispatcher6;
    
    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);
    protected abstract void onEvent3(E3 event);
    protected abstract void onEvent4(E4 event);
    protected abstract void onEvent5(E5 event);
    protected abstract void onEvent6(E6 event);

    public Node6<E1, E2, E3, E4, E5, E6> subscribe1(Dispatcher<E1> dispatcher1) {
        this.dispatcher1 = dispatcher1;
        return this;
    }
    
    public Node6<E1, E2, E3, E4, E5, E6> subscribe2(Dispatcher<E2> dispatcher2) {
        this.dispatcher2 = dispatcher2;
        return this;
    }
    
    public Node6<E1, E2, E3, E4, E5, E6> subscribe3(Dispatcher<E3> dispatcher3) {
        this.dispatcher3 = dispatcher3;
        return this;
    }
    
    public Node6<E1, E2, E3, E4, E5, E6> subscribe4(Dispatcher<E4> dispatcher4) {
        this.dispatcher4 = dispatcher4;
        return this;
    }
    
    public Node6<E1, E2, E3, E4, E5, E6> subscribe5(Dispatcher<E5> dispatcher5) {
        this.dispatcher5 = dispatcher5;
        return this;
    }
    
    public Node6<E1, E2, E3, E4, E5, E6> subscribe6(Dispatcher<E6> dispatcher6) {
        this.dispatcher6 = dispatcher6;
        return this;
    }

    public void start() {
        this.dispatcher1.register(this::onEvent1);
        this.dispatcher2.register(this::onEvent2);
        this.dispatcher3.register(this::onEvent3);
        this.dispatcher4.register(this::onEvent4);
        this.dispatcher5.register(this::onEvent5);
        this.dispatcher6.register(this::onEvent6);
    }
}