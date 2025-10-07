package io.rx.pipe.node;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;

public abstract class DisruptorNode7<E1, E2, E3, E4, E5, E6, E7> extends Node7<E1, E2, E3, E4, E5, E6, E7> {

    private final DisruptorComponent disruptorComponent = new DisruptorComponent();

    public DisruptorNode7<E1, E2, E3, E4, E5, E6, E7> bufferSize(int bufferSize) {
        disruptorComponent.setBufferSize(bufferSize);
        return this;
    }

    public DisruptorNode7<E1, E2, E3, E4, E5, E6, E7> waitStrategy(WaitStrategy waitStrategy) {
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
                case 4 -> onEvent4((E4) event.getPayload());
                case 5 -> onEvent5((E5) event.getPayload());
                case 6 -> onEvent6((E6) event.getPayload());
                case 7 -> onEvent7((E7) event.getPayload());
            }
        });

        RingBuffer<Event> ringBuffer = disruptor.start();
        this.disruptorComponent.setRingBuffer(ringBuffer);
        this.disruptorComponent.register(
                this.dispatcher1,
                this.dispatcher2,
                this.dispatcher3,
                this.dispatcher4,
                this.dispatcher5,
                this.dispatcher6,
                this.dispatcher7
        );
    }
}