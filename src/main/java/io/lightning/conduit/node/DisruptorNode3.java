package io.lightning.conduit.node;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;

public abstract class DisruptorNode3<E1, E2, E3> extends Node3<E1, E2, E3> {

    private final DisruptorComponent disruptorComponent = new DisruptorComponent();

    public DisruptorNode3<E1, E2, E3> bufferSize(int bufferSize) {
        disruptorComponent.setBufferSize(bufferSize);
        return this;
    }

    public DisruptorNode3<E1, E2, E3> waitStrategy(WaitStrategy waitStrategy) {
        disruptorComponent.setWaitStrategy(waitStrategy);
        return this;
    }

    @Override
    public void start() {
        Disruptor<Event> disruptor = disruptorComponent.getDisruptor();
        disruptor.handleEventsWith((event, l, b) -> {
            switch (event.getType()) {
                case 1 -> onEvent1((E1) event.getPayload());
                case 2 -> onEvent2((E2) event.getPayload());
                case 3 -> onEvent3((E3) event.getPayload());
            }
        });

        RingBuffer<Event> ringBuffer = disruptor.start();
        this.disruptorComponent.setRingBuffer(ringBuffer);
        this.disruptorComponent.register(
                this.dispatcher1,
                this.dispatcher2,
                this.dispatcher3
        );
    }
}