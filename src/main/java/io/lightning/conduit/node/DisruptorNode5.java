package io.lightning.conduit.node;

import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import io.lightning.conduit.dispatcher.Dispatcher;

import java.util.concurrent.ThreadFactory;

/**
 * {@link Node5} whose handlers run on a dedicated consumer thread fed by a ring buffer.
 * {@code onTrigger()}/{@code afterEvent()} run at the end of each batch drained from the ring.
 */
public abstract class DisruptorNode5<E1, E2, E3, E4, E5> extends Node5<E1, E2, E3, E4, E5> {

    private final DisruptorComponent disruptor = new DisruptorComponent(this);

    public DisruptorNode5<E1, E2, E3, E4, E5> bufferSize(int bufferSize) {
        checkNotStarted();
        disruptor.bufferSize(bufferSize);
        return this;
    }

    public DisruptorNode5<E1, E2, E3, E4, E5> waitStrategy(WaitStrategy waitStrategy) {
        checkNotStarted();
        disruptor.waitStrategy(waitStrategy);
        return this;
    }

    public DisruptorNode5<E1, E2, E3, E4, E5> producerType(ProducerType producerType) {
        checkNotStarted();
        disruptor.producerType(producerType);
        return this;
    }

    public DisruptorNode5<E1, E2, E3, E4, E5> threadFactory(ThreadFactory threadFactory) {
        checkNotStarted();
        disruptor.threadFactory(threadFactory);
        return this;
    }

    public DisruptorNode5<E1, E2, E3, E4, E5> backpressure(DisruptorComponent.Backpressure backpressure) {
        checkNotStarted();
        disruptor.backpressure(backpressure);
        return this;
    }

    /** Events rejected under {@link DisruptorComponent.Backpressure#DROP}. */
    public long droppedCount() {
        return disruptor.droppedCount();
    }

    /** {@code true} once a handler failed under {@link io.lightning.conduit.ErrorPolicy#HALT}. */
    public boolean isHalted() {
        return disruptor.isHalted();
    }

    /** Stops the consumer thread immediately without draining. Call {@link #stop()} to unsubscribe. */
    public void halt() {
        disruptor.halt();
    }

    @Override
    protected final boolean isSynchronous() {
        return false;
    }

    @Override
    protected void doStart(Dispatcher<?>[] inputs) {
        disruptor.start(inputs, this::deliver, this::completeCycle);
    }

    @Override
    protected void doStop(Dispatcher<?>[] inputs) {
        disruptor.stop(inputs);
    }
}
