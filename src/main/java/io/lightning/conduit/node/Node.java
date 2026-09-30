package io.lightning.conduit.node;

public interface Node {
    /** Validates wiring and subscribes the node. Idempotent. */
    void start();

    /** Unsubscribes the node. Idempotent. */
    default void stop() {
    }
}
