package io.lightning.conduit.node;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;

public abstract class DisruptorNode2<E1, E2> extends Node2<E1, E2> {

    private final DisruptorComponent disruptorComponent = new DisruptorComponent();

    public DisruptorNode2<E1, E2> bufferSize(int bufferSize) {
        disruptorComponent.setBufferSize(bufferSize);
        return this;
    }

    public DisruptorNode2<E1, E2> waitStrategy(WaitStrategy waitStrategy) {
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
           }
        });

        RingBuffer<Event> ringBuffer = disruptor.start();
        this.disruptorComponent.setRingBuffer(ringBuffer);
        this.disruptorComponent.register(
                this.dispatcher1,
                this.dispatcher2
        );
    }
}
