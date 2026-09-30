package io.lightning.conduit.dispatcher;

final class DefaultEventDispatcher<E> implements EventDispatcher<E> {
    private final ListenerRegistry<E> listeners = new ListenerRegistry<>();

    @Override
    public ListenerRegistry<E> listeners() {
        return listeners;
    }

    @Override
    public String toString() {
        return "EventDispatcher@" + Integer.toHexString(System.identityHashCode(this))
                + "[listeners=" + listeners.size() + "]";
    }
}
