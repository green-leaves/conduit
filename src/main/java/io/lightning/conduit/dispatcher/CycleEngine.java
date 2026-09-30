package io.lightning.conduit.dispatcher;

import io.lightning.conduit.Conduit;
import io.lightning.conduit.ConduitException;
import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher.Listener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Per-thread cycle executor for synchronous graphs.
 *
 * <p>An external dispatch runs a cycle: the event is handed to the dispatcher's listeners (node
 * inputs are queued on their node, plain listeners are called), then dirty nodes run in
 * topological order from a min-heap keyed by (rank, start order), then end-of-cycle hooks run.
 *
 * <p>Any dispatch made while user code runs is staged in a work queue and never recurses. When the
 * user code returns, staged entries are released: a dispatch whose source ranks above the
 * currently running level (or is the running node's own output) is delivered in this cycle, since
 * all of its listeners are still ahead in topological order; anything else is queued as a
 * follow-up cycle.
 *
 * <p>All arrays grow on demand and are reused, so steady-state dispatch does not allocate.
 */
final class CycleEngine {
    private static final Logger LOG = LoggerFactory.getLogger(CycleEngine.class);
    private static final ThreadLocal<CycleEngine> CURRENT = ThreadLocal.withInitial(CycleEngine::new);

    private boolean inCycle;
    /** Rank of the level currently running; listeners must rank above it to run in this cycle. */
    private int currentRank;
    /** Node whose handlers are running, or null. */
    private GraphNode firing;

    private GraphNode[] heap = new GraphNode[16];
    private int heapSize;

    private GraphNode[] changed = new GraphNode[16];
    private int changedCount;

    // staged dispatches of the user code currently running
    private Object[] wSource = new Object[16];
    private Object[] wTargets = new Object[16];
    private Object[] wEvent = new Object[16];
    private boolean[] wNext = new boolean[16];
    private int wHead;
    private int wTail;

    // follow-up cycles
    private Object[] nSource = new Object[16];
    private Object[] nTargets = new Object[16];
    private Object[] nEvent = new Object[16];
    private int nHead;
    private int nTail;

    static void dispatch(Object source, Object targets, Object event) {
        CURRENT.get().submit(source, targets, event);
    }

    static void checkIdle(String action) {
        if (CURRENT.get().inCycle) {
            throw new IllegalStateException("cannot " + action + " a node from inside a running cycle");
        }
    }

    private void submit(Object source, Object targets, Object event) {
        if (!inCycle) {
            run(source, targets, event);
            return;
        }
        int srcRank = rankOf(source);
        boolean next = srcRank <= currentRank && (firing == null || source != firing);
        stage(source, targets, event, next);
    }

    // ------------------------------------------------------------------ cycle driver

    private void run(Object source, Object targets, Object event) {
        inCycle = true;
        try {
            runCycle(source, targets, event);
            int chained = 0;
            int max = Conduit.maxChainedCycles();
            while (nHead < nTail) {
                if (++chained > max) {
                    throw new ConduitException("more than " + max
                            + " chained cycles for one dispatch; re-entrant feedback loop?");
                }
                int i = nHead++;
                Object s = nSource[i], t = nTargets[i], e = nEvent[i];
                nSource[i] = null;
                nTargets[i] = null;
                nEvent[i] = null;
                if (nHead == nTail) nHead = nTail = 0;
                runCycle(s, t, e);
            }
        } catch (RuntimeException | Error e) {
            reset();
            throw e;
        } finally {
            inCycle = false;
        }
    }

    private void runCycle(Object source, Object targets, Object event) {
        currentRank = 0;
        firing = null;
        deliver(source, targets, event);
        drainWork();
        while (heapSize > 0) {
            fire(poll());
        }
        endCycle();
    }

    private void fire(GraphNode node) {
        node.dirty = false;
        currentRank = node.rank;
        firing = node;
        int count = node.pendingCount;
        for (int i = 0; i < count; i++) {
            int input = node.pendingInput(i);
            Object event = node.takePendingEvent(i);
            int mark = wTail;
            try {
                node.deliver(input, event);
            } catch (Throwable t) {
                onFailure(node, event, t, mark);
            }
        }
        node.pendingCount = 0;

        boolean changedNow = true;
        if (node.hasTrigger) {
            int mark = wTail;
            try {
                changedNow = node.onTrigger();
            } catch (Throwable t) {
                onFailure(node, null, t, mark);
                changedNow = node.errorPolicy() == ErrorPolicy.LOG_CONTINUE;
            }
        }
        firing = null;
        if (changedNow) {
            if (node.hasAfterEvent) addChanged(node);
        } else {
            truncateWork(wHead);
        }
        drainWork();
    }

    private void endCycle() {
        currentRank = Integer.MAX_VALUE;
        firing = null;
        for (int i = 0; i < changedCount; i++) {
            GraphNode node = changed[i];
            changed[i] = null;
            int mark = wTail;
            try {
                node.afterEvent();
            } catch (Throwable t) {
                onFailure(node, null, t, mark);
            }
            drainWork();
        }
        changedCount = 0;
    }

    // ------------------------------------------------------------------ delivery

    private void deliver(Object source, Object targets, Object event) {
        if (targets instanceof Listener<?>[] listeners) {
            for (Listener<?> l : listeners) deliverTo(source, l, event);
        } else {
            deliverTo(source, (Listener<?>) targets, event);
        }
    }

    @SuppressWarnings("unchecked")
    private void deliverTo(Object source, Listener<?> listener, Object event) {
        if (listener instanceof NodeInput in) {
            GraphNode node = in.node;
            if (!node.isStarted()) return;
            node.enqueue(in.index, event);
            if (!node.dirty) {
                node.dirty = true;
                push(node);
            }
            return;
        }
        // a plain listener behaves like a pseudo-node sitting just below its source
        int savedRank = currentRank;
        GraphNode savedFiring = firing;
        currentRank = rankOf(source);
        firing = null;
        int mark = wTail;
        try {
            ((Listener<Object>) listener).onEvent(event);
        } catch (Throwable t) {
            onFailure(listener, event, t, mark);
        } finally {
            currentRank = savedRank;
            firing = savedFiring;
        }
    }

    private void onFailure(Object source, Object event, Throwable error, int mark) {
        Conduit.reportError(source, event, error);
        if (error instanceof VirtualMachineError vme) throw vme;
        ErrorPolicy policy = source instanceof GraphNode g ? g.errorPolicy() : Conduit.defaultErrorPolicy();
        switch (policy) {
            case HALT -> throw error instanceof ConduitException ce ? ce
                    : new ConduitException("handler failed in " + describe(source), source, error);
            case SKIP_EVENT -> truncateWork(mark);
            case LOG_CONTINUE -> LOG.warn("handler failed in {}", describe(source), error);
        }
    }

    private static String describe(Object source) {
        return source instanceof GraphNode g ? g.describe() : String.valueOf(source);
    }

    private static int rankOf(Object source) {
        return source instanceof GraphNode g ? g.outRank : 0;
    }

    // ------------------------------------------------------------------ work queue

    private void stage(Object source, Object targets, Object event, boolean next) {
        if (wTail == wSource.length) {
            int n = wTail * 2;
            wSource = Arrays.copyOf(wSource, n);
            wTargets = Arrays.copyOf(wTargets, n);
            wEvent = Arrays.copyOf(wEvent, n);
            wNext = Arrays.copyOf(wNext, n);
        }
        wSource[wTail] = source;
        wTargets[wTail] = targets;
        wEvent[wTail] = event;
        wNext[wTail] = next;
        wTail++;
    }

    private void drainWork() {
        while (wHead < wTail) {
            int i = wHead++;
            Object s = wSource[i], t = wTargets[i], e = wEvent[i];
            boolean next = wNext[i];
            wSource[i] = null;
            wTargets[i] = null;
            wEvent[i] = null;
            if (next) {
                enqueueNext(s, t, e);
            } else {
                deliver(s, t, e);
            }
        }
        wHead = wTail = 0;
    }

    private void truncateWork(int mark) {
        for (int i = mark; i < wTail; i++) {
            wSource[i] = null;
            wTargets[i] = null;
            wEvent[i] = null;
        }
        wTail = mark;
    }

    private void enqueueNext(Object source, Object targets, Object event) {
        if (nTail == nSource.length) {
            if (nHead > 0) {
                int len = nTail - nHead;
                System.arraycopy(nSource, nHead, nSource, 0, len);
                System.arraycopy(nTargets, nHead, nTargets, 0, len);
                System.arraycopy(nEvent, nHead, nEvent, 0, len);
                Arrays.fill(nSource, len, nTail, null);
                Arrays.fill(nTargets, len, nTail, null);
                Arrays.fill(nEvent, len, nTail, null);
                nHead = 0;
                nTail = len;
            } else {
                int n = nTail * 2;
                nSource = Arrays.copyOf(nSource, n);
                nTargets = Arrays.copyOf(nTargets, n);
                nEvent = Arrays.copyOf(nEvent, n);
            }
        }
        nSource[nTail] = source;
        nTargets[nTail] = targets;
        nEvent[nTail] = event;
        nTail++;
    }

    private void addChanged(GraphNode node) {
        if (changedCount == changed.length) changed = Arrays.copyOf(changed, changedCount * 2);
        changed[changedCount++] = node;
    }

    /** Discards all in-flight state after a failure escaped the cycle. */
    private void reset() {
        for (int i = 0; i < heapSize; i++) {
            heap[i].clearPending();
            heap[i] = null;
        }
        heapSize = 0;
        if (firing != null) firing.clearPending();
        firing = null;
        Arrays.fill(changed, 0, changedCount, null);
        changedCount = 0;
        truncateWork(0);
        wHead = 0;
        for (int i = nHead; i < nTail; i++) {
            nSource[i] = null;
            nTargets[i] = null;
            nEvent[i] = null;
        }
        nHead = nTail = 0;
        currentRank = 0;
    }

    // ------------------------------------------------------------------ min-heap by (rank, seq)

    private static boolean before(GraphNode a, GraphNode b) {
        return a.rank != b.rank ? a.rank < b.rank : a.seq < b.seq;
    }

    private void push(GraphNode node) {
        if (heapSize == heap.length) heap = Arrays.copyOf(heap, heapSize * 2);
        int i = heapSize++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            GraphNode p = heap[parent];
            if (!before(node, p)) break;
            heap[i] = p;
            i = parent;
        }
        heap[i] = node;
    }

    private GraphNode poll() {
        GraphNode top = heap[0];
        GraphNode last = heap[--heapSize];
        heap[heapSize] = null;
        if (heapSize > 0) {
            int i = 0;
            int half = heapSize >>> 1;
            while (i < half) {
                int child = 2 * i + 1;
                int right = child + 1;
                if (right < heapSize && before(heap[right], heap[child])) child = right;
                if (!before(heap[child], last)) break;
                heap[i] = heap[child];
                i = child;
            }
            heap[i] = last;
        }
        return top;
    }
}
