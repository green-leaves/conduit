package io.rx.pipe.dispatcher;

public interface Dispatcher<E> {
    void register(EventDispatcher.Listener<E> listener);
    boolean unregister(EventDispatcher.Listener<E> listener);
    void clear();
    void dispatch(E event);
}