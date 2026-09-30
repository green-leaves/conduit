package io.lightning.conduit.node;

import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;

public abstract class Node7<E1, E2, E3, E4, E5, E6, E7> extends GraphNode implements Node {
    protected Dispatcher<E1> dispatcher1;
    protected Dispatcher<E2> dispatcher2;
    protected Dispatcher<E3> dispatcher3;
    protected Dispatcher<E4> dispatcher4;
    protected Dispatcher<E5> dispatcher5;
    protected Dispatcher<E6> dispatcher6;
    protected Dispatcher<E7> dispatcher7;

    protected abstract void onEvent1(E1 event);
    protected abstract void onEvent2(E2 event);
    protected abstract void onEvent3(E3 event);
    protected abstract void onEvent4(E4 event);
    protected abstract void onEvent5(E5 event);
    protected abstract void onEvent6(E6 event);
    protected abstract void onEvent7(E7 event);

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe1(Dispatcher<E1> dispatcher1) {
        checkNotStarted();
        this.dispatcher1 = dispatcher1;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe2(Dispatcher<E2> dispatcher2) {
        checkNotStarted();
        this.dispatcher2 = dispatcher2;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe3(Dispatcher<E3> dispatcher3) {
        checkNotStarted();
        this.dispatcher3 = dispatcher3;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe4(Dispatcher<E4> dispatcher4) {
        checkNotStarted();
        this.dispatcher4 = dispatcher4;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe5(Dispatcher<E5> dispatcher5) {
        checkNotStarted();
        this.dispatcher5 = dispatcher5;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe6(Dispatcher<E6> dispatcher6) {
        checkNotStarted();
        this.dispatcher6 = dispatcher6;
        return this;
    }

    public Node7<E1, E2, E3, E4, E5, E6, E7> subscribe7(Dispatcher<E7> dispatcher7) {
        checkNotStarted();
        this.dispatcher7 = dispatcher7;
        return this;
    }

    @Override
    protected final Dispatcher<?>[] inputs() {
        return new Dispatcher<?>[]{dispatcher1, dispatcher2, dispatcher3, dispatcher4, dispatcher5, dispatcher6, dispatcher7};
    }

    @Override
    @SuppressWarnings("unchecked")
    protected final void deliver(int input, Object event) {
        switch (input) {
            case 1 -> onEvent1((E1) event);
            case 2 -> onEvent2((E2) event);
            case 3 -> onEvent3((E3) event);
            case 4 -> onEvent4((E4) event);
            case 5 -> onEvent5((E5) event);
            case 6 -> onEvent6((E6) event);
            case 7 -> onEvent7((E7) event);
            default -> throw new IllegalArgumentException("no input " + input);
        }
    }
}
