package io.lightning.conduit.bench;

import io.lightning.conduit.dispatcher.EventDispatcher;
import io.lightning.conduit.dispatcher.ListenerRegistry;
import io.lightning.conduit.node.Node1;
import io.lightning.conduit.node.Node2;

/**
 * Benchmark topologies. Nodes pass the same payload instance through, so any allocation measured
 * comes from the framework, not from user code.
 */
public final class Graphs {
    private Graphs() {
    }

    /** Forwards its input unchanged. */
    public static final class Pass extends Node1<Object> implements EventDispatcher<Object> {
        private final ListenerRegistry<Object> listeners = new ListenerRegistry<>();

        @Override
        public ListenerRegistry<Object> listeners() {
            return listeners;
        }

        @Override
        protected void onEvent1(Object event) {
            dispatch(event);
        }
    }

    /** Terminal node counting what reaches it. */
    public static final class Tail extends Node1<Object> {
        public long count;
        public Object last;

        @Override
        protected void onEvent1(Object event) {
            count++;
            last = event;
        }
    }

    /** Two-input join that fires once per cycle. */
    public static final class Join extends Node2<Object, Object> {
        public long triggers;
        Object a;
        Object b;

        @Override
        protected void onEvent1(Object event) {
            a = event;
        }

        @Override
        protected void onEvent2(Object event) {
            b = event;
        }

        @Override
        protected boolean onTrigger() {
            triggers++;
            return true;
        }
    }

    /** {@code src -> Pass x (hops - 1) -> Tail}: {@code hops} node hops in total. */
    public static EventDispatcher<Object> chain(int hops, Tail tail) {
        EventDispatcher<Object> src = EventDispatcher.create();
        EventDispatcher<Object> prev = src;
        for (int i = 0; i < hops - 1; i++) {
            Pass p = new Pass();
            p.subscribe1(prev).start();
            prev = p;
        }
        tail.subscribe1(prev).start();
        return src;
    }

    /** {@code tick -> A, tick -> B, Join(A, B)}. */
    public static EventDispatcher<Object> diamond(Join join) {
        EventDispatcher<Object> tick = EventDispatcher.create();
        Pass a = new Pass();
        Pass b = new Pass();
        a.subscribe1(tick).start();
        b.subscribe1(tick).start();
        join.subscribe1(a).subscribe2(b).start();
        return tick;
    }
}
