package io.lightning.conduit.dispatcher;

public interface Dispatcher<E> {
    /**
     * Single-method functional interface (acts as the callback type).
     */
    @FunctionalInterface
    interface Listener<E> {
        void onEvent(E event);
    }

    /** Registers a listener. Registering the same listener instance twice has no effect. */
    void register(Listener<E> listener);
    boolean unregister(Listener<E> listener);
    void clear();
    void dispatch(E event);
}
