package io.lightning.conduit;

/**
 * Thrown to the dispatching caller when a handler fails under {@link ErrorPolicy#HALT}, or when
 * the graph is misused (cycles, runaway re-entrant dispatch, dispatch into a halted node).
 */
public class ConduitException extends RuntimeException {
    private final transient Object source;

    public ConduitException(String message) {
        this(message, null, null);
    }

    public ConduitException(String message, Object source, Throwable cause) {
        super(message, cause);
        this.source = source;
    }

    /** The node or listener that failed, if known. */
    public Object getSource() {
        return source;
    }
}
