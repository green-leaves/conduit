package io.lightning.conduit.graph;

import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Reusable nodes for engine tests. */
final class TestNodes {
    private TestNodes() {
    }

    /** Applies a function to each input and dispatches the result; logs "name:in". */
    static class MapNode<I, O> extends Node1<I> implements EventDispatcher<O> {
        private final ListenerRegistry<O> listeners = new ListenerRegistry<>();
        final String name;
        final Function<I, O> fn;
        final List<String> log;
        O last;

        MapNode(String name, List<String> log, Function<I, O> fn) {
            this.name = name;
            this.log = log;
            this.fn = fn;
        }

        @Override
        public ListenerRegistry<O> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(I event) {
            log.add(name + ":" + event);
            last = fn.apply(event);
            dispatch(last);
        }
    }

    /** Joins two inputs; records and dispatches the combined state once per cycle from onTrigger. */
    static class JoinNode<A, B> extends Node2<A, B> implements EventDispatcher<String> {
        private final ListenerRegistry<String> listeners = new ListenerRegistry<>();
        final List<String> triggers = new ArrayList<>();
        final List<String> inputs = new ArrayList<>();
        A a;
        B b;

        @Override
        public ListenerRegistry<String> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(A event) {
            inputs.add("1:" + event);
            a = event;
        }

        @Override
        protected void onEvent2(B event) {
            inputs.add("2:" + event);
            b = event;
        }

        @Override
        protected boolean onTrigger() {
            String state = a + "," + b;
            triggers.add(state);
            dispatch(state);
            return true;
        }
    }

    /** Records its input into a shared log. */
    static class Sink<T> extends Node1<T> {
        final String name;
        final List<String> log;
        final List<T> received = new ArrayList<>();

        Sink(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        protected void onEvent1(T event) {
            log.add(name + ":" + event);
            received.add(event);
        }
    }
}
