package io.lightning.conduit.node;

import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import io.lightning.conduit.Conduit;
import io.lightning.conduit.ConduitException;
import io.lightning.conduit.ErrorPolicy;
import io.lightning.conduit.dispatcher.Dispatcher;
import io.lightning.conduit.dispatcher.GraphNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Ring buffer and consumer thread behind a Disruptor node. Producers publish from whatever thread
 * dispatches to the node's inputs; the node's handlers run on the consumer thread.
 *
 * <p>Handler failures never kill the consumer thread. Under {@link io.lightning.conduit.ErrorPolicy#HALT}
 * the node is marked halted: the consumer drains and discards remaining events (so producers never
 * stall on a full ring) and further dispatches to the node throw {@link ConduitException}.
 *
 * <p>The consumer thread is a containment boundary: when a handler dispatches into a synchronous
 * graph and a node there fails under {@code HALT}, that cycle is aborted and the event dropped, but
 * this node keeps running. Only failures of this node's own handlers apply its policy.
 */
public final class DisruptorComponent {
    private static final Logger LOG = LoggerFactory.getLogger(DisruptorComponent.class);
    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

    private static final EventTranslatorTwoArg<Event<Object>, Object, Integer> TRANSLATOR =
            (event, sequence, payload, type) -> event.set(payload, type);

    /** What a producer does when the ring buffer is full. */
    public enum Backpressure {
        /** Wait for space (default). Never loses events; a slow consumer slows its producers. */
        BLOCK,
        /** Drop the new event and count it in {@link #droppedCount()}. Producers never wait. */
        DROP
    }

    /** Receives each event on the consumer thread. */
    @FunctionalInterface
    public interface Sink {
        void deliver(int input, Object payload);
    }

    private final GraphNode owner;
    private int bufferSize = 1024;
    private WaitStrategy waitStrategy = new BusySpinWaitStrategy();
    private ProducerType producerType = ProducerType.MULTI;
    private ThreadFactory threadFactory;
    private Backpressure backpressure = Backpressure.BLOCK;

    private Disruptor<Event<Object>> disruptor;
    private RingBuffer<Event<Object>> ringBuffer;
    private Dispatcher.Listener<Object>[] publishers;
    private volatile boolean halted;
    private final LongAdder dropped = new LongAdder();

    public DisruptorComponent(GraphNode owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
    }

    // ------------------------------------------------------------------ configuration

    public void bufferSize(int bufferSize) {
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2: " + bufferSize);
        }
        this.bufferSize = bufferSize;
    }

    public void waitStrategy(WaitStrategy waitStrategy) {
        this.waitStrategy = Objects.requireNonNull(waitStrategy, "waitStrategy");
    }

    /** Use {@link ProducerType#SINGLE} only if every input is dispatched from one and the same thread. */
    public void producerType(ProducerType producerType) {
        this.producerType = Objects.requireNonNull(producerType, "producerType");
    }

    /** Factory for the consumer thread, e.g. to pin it to a core. Default: named daemon thread. */
    public void threadFactory(ThreadFactory threadFactory) {
        this.threadFactory = Objects.requireNonNull(threadFactory, "threadFactory");
    }

    public void backpressure(Backpressure backpressure) {
        this.backpressure = Objects.requireNonNull(backpressure, "backpressure");
    }

    public int bufferSize() {
        return bufferSize;
    }

    public long droppedCount() {
        return dropped.sum();
    }

    public boolean isHalted() {
        return halted;
    }

    /** The running Disruptor, or {@code null} when stopped. */
    public Disruptor<Event<Object>> disruptor() {
        return disruptor;
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressWarnings("unchecked")
    public void start(Dispatcher<?>[] inputs, Sink sink, Runnable endOfBatch) {
        halted = false;
        ThreadFactory factory = threadFactory != null ? threadFactory : defaultThreadFactory();
        Disruptor<Event<Object>> d = new Disruptor<>(Event::new, bufferSize, factory, producerType, waitStrategy);
        d.handleEventsWith((EventHandler<Event<Object>>) (event, sequence, endOfBatchFlag) -> {
            if (!halted) {
                try {
                    sink.deliver(event.getType(), event.getPayload());
                } catch (Throwable t) {
                    fail(event.getPayload(), t);
                }
            }
            event.clear();
            if (endOfBatchFlag && !halted) {
                try {
                    endOfBatch.run();
                } catch (Throwable t) {
                    fail(null, t);
                }
            }
        });
        d.setDefaultExceptionHandler(new SafetyNet());
        ringBuffer = d.start();
        disruptor = d;

        publishers = new Dispatcher.Listener[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            Integer type = i + 1;
            publishers[i] = payload -> publish(payload, type);
            ((Dispatcher<Object>) inputs[i]).register(publishers[i]);
        }
    }

    /** Unsubscribes, lets in-flight events drain (up to 5s), then stops the consumer thread. */
    @SuppressWarnings("unchecked")
    public void stop(Dispatcher<?>[] inputs) {
        if (publishers != null) {
            for (int i = 0; i < inputs.length; i++) {
                ((Dispatcher<Object>) inputs[i]).unregister(publishers[i]);
            }
            publishers = null;
        }
        Disruptor<Event<Object>> d = disruptor;
        if (d == null) return;
        // Disruptor.shutdown() only counts backlog on consumers already running, so it can halt a
        // consumer whose thread has not started yet and lose everything; wait on the sequence instead.
        RingBuffer<Event<Object>> rb = ringBuffer;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_TIMEOUT_MS);
        while (rb.getMinimumGatingSequence() < rb.getCursor() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(50_000);
        }
        d.halt();
        disruptor = null;
        ringBuffer = null;
    }

    /** Stops the consumer thread immediately; queued events are not processed. */
    public void halt() {
        halted = true;
        Disruptor<Event<Object>> d = disruptor;
        if (d != null) d.halt();
    }

    // ------------------------------------------------------------------ internals

    private void publish(Object payload, Integer type) {
        if (halted) {
            throw new ConduitException(owner + " is halted after a handler failure", owner, null);
        }
        RingBuffer<Event<Object>> rb = ringBuffer;
        if (rb == null) return;   // raced with stop()
        if (backpressure == Backpressure.DROP) {
            if (!rb.tryPublishEvent(TRANSLATOR, payload, type)) dropped.increment();
        } else {
            rb.publishEvent(TRANSLATOR, payload, type);
        }
    }

    private void fail(Object event, Throwable error) {
        if (isDownstreamFailure(error)) {
            // A synchronous cycle this node drove from its handler failed under HALT. That cycle
            // already reported the failure and discarded its work; the node itself is healthy.
            LOG.warn("downstream cycle driven by {} failed in {}; event dropped",
                    owner, ((ConduitException) error).getSource(), error);
            return;
        }
        // update state before notifying, so observers of the error handler see the node halted
        boolean fatal = error instanceof VirtualMachineError;
        ErrorPolicy policy = fatal ? ErrorPolicy.HALT : owner.errorPolicy();
        if (policy == ErrorPolicy.HALT) halted = true;
        Conduit.reportError(owner, event, error);
        switch (policy) {
            case HALT -> LOG.error(fatal ? "fatal error in {}; node halted" : "handler failed in {}; node halted",
                    owner, error);
            case SKIP_EVENT -> { }
            case LOG_CONTINUE -> LOG.warn("handler failed in {}", owner, error);
        }
    }

    /**
     * A {@link ConduitException} attributed to another node or listener can only reach the consumer
     * thread by escaping a synchronous cycle the handler started with {@code dispatch} (HALT policy
     * of the downstream node, or a dispatch into a halted Disruptor node).
     */
    private boolean isDownstreamFailure(Throwable error) {
        return error instanceof ConduitException ce && ce.getSource() != null && ce.getSource() != owner;
    }

    private ThreadFactory defaultThreadFactory() {
        String name = "conduit-" + owner.getClass().getSimpleName() + "-" + THREAD_IDS.incrementAndGet();
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /** Last line of defence: anything escaping the handler is reported instead of killing the thread. */
    private final class SafetyNet implements ExceptionHandler<Event<Object>> {
        @Override
        public void handleEventException(Throwable ex, long sequence, Event<Object> event) {
            fail(event == null ? null : event.getPayload(), ex);
        }

        @Override
        public void handleOnStartException(Throwable ex) {
            Conduit.reportError(owner, null, ex);
            LOG.error("start failed in {}", owner, ex);
        }

        @Override
        public void handleOnShutdownException(Throwable ex) {
            Conduit.reportError(owner, null, ex);
            LOG.error("shutdown failed in {}", owner, ex);
        }
    }
}
