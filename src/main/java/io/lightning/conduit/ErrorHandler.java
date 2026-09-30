package io.lightning.conduit;

/**
 * Receives every handler failure regardless of {@link ErrorPolicy}. Called on the thread that ran
 * the failing handler; implementations must be cheap and must not throw.
 */
@FunctionalInterface
public interface ErrorHandler {
    ErrorHandler NO_OP = (source, event, error) -> { };

    /**
     * @param source the node or listener whose handler failed
     * @param event  the event being handled, or {@code null} for trigger/end-of-cycle hooks
     * @param error  the failure
     */
    void onError(Object source, Object event, Throwable error);
}
