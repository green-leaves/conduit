package io.rx.pipe.node;

import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.rx.pipe.dispatcher.Dispatcher;
import lombok.Getter;
import lombok.Setter;
import lombok.val;

@Getter
@Setter
public class DisruptorComponent {
    private int bufferSize = 1024;
    private Disruptor<Event> disruptor;
    private RingBuffer<Event> ringBuffer;
    private WaitStrategy waitStrategy = new BusySpinWaitStrategy();

    public Disruptor<Event> getDisruptor() {
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
    public void register(Dispatcher ... dispatchers) {
        for (int i = 0; i < dispatchers.length; i++) {
            val dispatcher = dispatchers[i];
            final int index = i + 1;
            dispatcher.register(e -> {
                long sequence = ringBuffer.next();
                Event event = ringBuffer.get(sequence);
                event.set(e, index);
                ringBuffer.publish(sequence);
            });
        }

    }
}
