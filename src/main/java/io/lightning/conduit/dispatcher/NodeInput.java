package io.lightning.conduit.dispatcher;

/**
 * The listener a synchronous {@link GraphNode} registers on each of its input dispatchers.
 * The engine recognises it and queues the event on the node instead of calling it immediately.
 */
final class NodeInput implements Dispatcher.Listener<Object> {
    final GraphNode node;
    final int index;

    NodeInput(GraphNode node, int index) {
        this.node = node;
        this.index = index;
    }

    /** Only reached when a custom {@link Dispatcher} calls its listeners directly. */
    @Override
    public void onEvent(Object event) {
        CycleEngine.dispatch(null, this, event);
    }

    @Override
    public String toString() {
        return node.describe() + "#input" + index;
    }
}
