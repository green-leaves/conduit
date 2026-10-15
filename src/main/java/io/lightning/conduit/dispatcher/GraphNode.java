package io.lightning.conduit.dispatcher;

import io.lightning.conduit.ConduitException;
import io.lightning.conduit.Conduit;
import io.lightning.conduit.ErrorPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Base class of every node. Holds the node's place in the dependency graph and the per-cycle
 * scheduling state used by the engine.
 *
 * <h2>Cycle semantics (synchronous nodes)</h2>
 * An external dispatch starts a <em>cycle</em>. Nodes run in topological order (by
 * {@link #rank()}, ties broken by {@link #start()} order). A node runs at most once per cycle,
 * after every upstream node that fed it has finished: all of its inputs for the cycle are delivered
 * to {@code onEventN} in arrival order, then {@link #onTrigger()} is called once. At the end of the
 * cycle {@link #afterEvent()} runs on each node whose trigger reported a change.
 *
 * <p>Everything a node dispatches while it runs is staged and only released to its children when
 * the node finishes. If {@link #onTrigger()} returns {@code false} the staged output is dropped,
 * so unchanged branches are skipped.
 *
 * <p>A synchronous graph must be driven by one thread at a time; use a Disruptor node as the
 * boundary between threads.
 */
public abstract class GraphNode {
    private static final Object TOPOLOGY = new Object();
    private static long nextSeq;

    // --- topology (guarded by TOPOLOGY; rank/seq/outRank published to dispatch threads via the
    //     volatile write in ListenerRegistry.add) ---
    int rank;
    long seq;
    /** Rank this node has as a source: its rank while started and synchronous, else 0. */
    int outRank;
    private volatile boolean started;
    private final List<GraphNode> children = new ArrayList<>();
    private Dispatcher<?>[] boundInputs;
    private NodeInput[] nodeInputs;
    private volatile ErrorPolicy errorPolicy;

    // --- per-cycle scheduling state (touched only by the thread driving the graph) ---
    boolean dirty;
    boolean hasTrigger;
    boolean hasAfterEvent;
    private int[] pendingInput = new int[4];
    private Object[] pendingEvent = new Object[4];
    int pendingCount;

    protected GraphNode() {
    }

    /** The wired input dispatchers, in input order (element 0 is input 1). Unwired inputs are null. */
    protected abstract Dispatcher<?>[] inputs();

    /** Routes an event arriving on input {@code input} (1-based) to its handler. */
    protected abstract void deliver(int input, Object event);

    /**
     * Called once per cycle after all of this cycle's inputs have been delivered.
     *
     * @return {@code true} if the node changed. Returning {@code false} drops everything the node
     * dispatched during this cycle and skips its {@link #afterEvent()}.
     */
    protected boolean onTrigger() {
        return true;
    }

    /** End-of-cycle hook, called after every node in the cycle has run (e.g. publish-once-per-tick). */
    protected void afterEvent() {
    }

    /**
     * {@code false} for nodes that hand events to another thread (Disruptor nodes). Such nodes are
     * not scheduled by the engine and act as plain sources for their children.
     */
    protected boolean isSynchronous() {
        return true;
    }

    /** Start hook for non-synchronous nodes; inputs are already validated. */
    protected void doStart(Dispatcher<?>[] inputs) {
    }

    /** Stop hook for non-synchronous nodes. */
    protected void doStop(Dispatcher<?>[] inputs) {
    }

    /**
     * Validates wiring, places the node in the graph and subscribes it. Idempotent.
     *
     * @throws NullPointerException  if an input is not wired
     * @throws ConduitException      if the wiring forms a cycle
     * @throws IllegalStateException if called from inside a running cycle
     */
    public void start() {
        synchronized (TOPOLOGY) {
            if (started) return;
            CycleEngine.checkIdle("start");
            Dispatcher<?>[] in = inputs();
            for (int i = 0; i < in.length; i++) {
                int n = i + 1;
                Objects.requireNonNull(in[i], () -> "input " + n + " of " + describe()
                        + " is not wired (call subscribe" + n + " before start)");
            }
            if (!isSynchronous()) {
                doStart(in);
                boundInputs = in;
                started = true;
                return;
            }

            rank = computeRank(this, new HashMap<>(), new ArrayList<>());
            outRank = rank;
            seq = nextSeq++;
            hasTrigger = overrides(getClass(), "onTrigger");
            hasAfterEvent = overrides(getClass(), "afterEvent");
            for (Dispatcher<?> d : in) {
                GraphNode parent = syncParent(d);
                if (parent != null) parent.children.add(this);
            }
            bumpChildren(this);

            started = true;
            boundInputs = in;
            nodeInputs = new NodeInput[in.length];
            for (int i = 0; i < in.length; i++) {
                nodeInputs[i] = new NodeInput(this, i + 1);
                register(in[i], nodeInputs[i]);
            }
        }
    }

    /** Unsubscribes the node. It can be re-wired and started again. Idempotent. */
    public void stop() {
        synchronized (TOPOLOGY) {
            if (!started) return;
            CycleEngine.checkIdle("stop");
            if (isSynchronous()) {
                for (int i = 0; i < boundInputs.length; i++) {
                    unregister(boundInputs[i], nodeInputs[i]);
                    GraphNode parent = syncParent(boundInputs[i]);
                    if (parent != null) parent.children.remove(this);
                }
                nodeInputs = null;
                outRank = 0;
                clearPending();
            } else {
                doStop(boundInputs);
            }
            boundInputs = null;
            started = false;
        }
    }

    public final boolean isStarted() {
        return started;
    }

    /** Topological rank: 1 + the highest rank among synchronous upstream nodes (0 = not placed). */
    public final int rank() {
        return rank;
    }

    /**
     * Creates an output port owned by this node; typically assigned to a final field. Nodes
     * subscribed to the port are ordered after this node and receive its output in the same cycle.
     */
    protected final <E> Output<E> output() {
        return new Output<>(this);
    }

    /** Overrides {@link Conduit#defaultErrorPolicy()} for this node; {@code null} restores the default. */
    public GraphNode errorPolicy(ErrorPolicy policy) {
        this.errorPolicy = policy;
        return this;
    }

    /** The policy in effect for this node. */
    public final ErrorPolicy errorPolicy() {
        ErrorPolicy p = errorPolicy;
        return p != null ? p : Conduit.defaultErrorPolicy();
    }

    /** Guards re-wiring of a running node. */
    protected final void checkNotStarted() {
        if (started) {
            throw new IllegalStateException(describe() + " is started; stop() it before re-wiring");
        }
    }

    /**
     * Runs {@link #onTrigger()} and, if it reports a change, {@link #afterEvent()}. Used by
     * non-synchronous nodes at the end of a batch.
     */
    protected final void completeCycle() {
        if (onTrigger()) afterEvent();
    }

    String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }

    @Override
    public String toString() {
        return describe() + "[rank=" + rank + (started ? ", started" : "") + "]";
    }

    // ------------------------------------------------------------------ pending inputs

    final void enqueue(int input, Object event) {
        int n = pendingCount;
        if (n == pendingInput.length) {
            pendingInput = java.util.Arrays.copyOf(pendingInput, n * 2);
            pendingEvent = java.util.Arrays.copyOf(pendingEvent, n * 2);
        }
        pendingInput[n] = input;
        pendingEvent[n] = event;
        pendingCount = n + 1;
    }

    final int pendingInput(int i) {
        return pendingInput[i];
    }

    final Object takePendingEvent(int i) {
        Object e = pendingEvent[i];
        pendingEvent[i] = null;
        return e;
    }

    final void clearPending() {
        for (int i = 0; i < pendingCount; i++) pendingEvent[i] = null;
        pendingCount = 0;
        dirty = false;
    }

    // ------------------------------------------------------------------ topology helpers

    /** The node behind a dispatch source: the node itself, or the owner of an {@link Output}. */
    static GraphNode nodeOf(Object source) {
        if (source instanceof GraphNode g) return g;
        if (source instanceof Output<?> o) return o.owner();
        return null;
    }

    private static GraphNode syncParent(Dispatcher<?> d) {
        GraphNode g = nodeOf(d);
        return g != null && g.isSynchronous() ? g : null;
    }

    /** Longest path from a source, following synchronous node-dispatchers upstream. */
    private static int computeRank(GraphNode node, Map<GraphNode, Integer> memo, List<GraphNode> path) {
        Integer known = memo.get(node);
        if (known != null) return known;
        int at = path.indexOf(node);
        if (at >= 0) {
            StringBuilder sb = new StringBuilder("cycle in node graph: ");
            for (int i = at; i < path.size(); i++) sb.append(path.get(i).describe()).append(" <- ");
            throw new ConduitException(sb.append(node.describe()).toString());
        }
        path.add(node);
        int max = 0;
        for (Dispatcher<?> d : node.inputs()) {
            GraphNode parent = syncParent(d);
            if (parent != null) max = Math.max(max, computeRank(parent, memo, path));
        }
        path.remove(path.size() - 1);
        memo.put(node, max + 1);
        return max + 1;
    }

    private static void bumpChildren(GraphNode node) {
        for (GraphNode child : node.children) {
            if (child.rank <= node.rank) {
                child.rank = node.rank + 1;
                if (child.started) child.outRank = child.rank;
                bumpChildren(child);
            }
        }
    }

    private static boolean overrides(Class<?> type, String method) {
        for (Class<?> c = type; c != null && c != GraphNode.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod(method);
                return true;
            } catch (NoSuchMethodException ignored) {
                // keep looking up the hierarchy
            }
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void register(Dispatcher<?> d, NodeInput input) {
        ((Dispatcher) d).register(input);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void unregister(Dispatcher<?> d, NodeInput input) {
        ((Dispatcher) d).unregister(input);
    }
}
