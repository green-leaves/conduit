package io.lightning.conduit.node;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.lightning.conduit.dispatcher.Dispatcher;
import lombok.Getter;
import lombok.Setter;
import lombok.val;

@Getter
@Setter
public class DisruptorComponent<T> {
    private int bufferSize = 1024;
    private Disruptor<Event<T>> disruptor;
    private RingBuffer<Event<T>> ringBuffer;
    private WaitStrategy waitStrategy = new BusySpinWaitStrategy();

    public Disruptor<Event<T>> getDisruptor() {
        if (disruptor == null) {
            return new Disruptor<>(
                    Event::new,
                    bufferSize,
                    DaemonThreadFactory.INSTANCE,
                    ProducerType.MULTI,
                    waitStrategy
            );
        }
        return disruptor;
    }

    @SuppressWarnings("unchecked")
    public void register(Dispatcher<?> ... dispatchers) {
        for (int i = 0; i < dispatchers.length; i++) {
            Dispatcher<T> dispatcher = (Dispatcher<T>) dispatchers[i];
            final int index = i + 1;
            dispatcher.register(e -> {
                long sequence = ringBuffer.next();
                Event<T> event = ringBuffer.get(sequence);
                event.set(e, index);
                ringBuffer.publish(sequence);
            });
        }
    }
}
