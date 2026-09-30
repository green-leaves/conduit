package io.lightning.conduit.node;

/** Ring buffer slot: the input number the payload arrived on, and the payload itself. */
public class Event<T> {
    private int type;
    private T payload;

    public int getType() {
        return type;
    }

    public T getPayload() {
        return payload;
    }

    public void set(T payload, int type) {
        this.payload = payload;
        this.type = type;
    }

    /** Releases the payload so the ring buffer does not keep it reachable. */
    public void clear() {
        this.payload = null;
        this.type = 0;
    }
}
