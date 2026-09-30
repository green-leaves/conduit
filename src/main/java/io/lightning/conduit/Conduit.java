package io.lightning.conduit;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide defaults. Per-node settings (e.g. {@code node.errorPolicy(...)}) take precedence.
 */
public final class Conduit {
    private static volatile ErrorPolicy defaultErrorPolicy = ErrorPolicy.HALT;
    private static volatile ErrorHandler errorHandler = ErrorHandler.NO_OP;
    private static volatile int maxChainedCycles = 1_000_000;
    private static final LongAdder ERROR_COUNT = new LongAdder();

    private Conduit() {
    }

    public static ErrorPolicy defaultErrorPolicy() {
        return defaultErrorPolicy;
    }

    /** Policy for nodes without their own policy, and for plain listeners. */
    public static void defaultErrorPolicy(ErrorPolicy policy) {
        defaultErrorPolicy = Objects.requireNonNull(policy, "policy");
    }

    public static ErrorHandler errorHandler() {
        return errorHandler;
    }

    public static void errorHandler(ErrorHandler handler) {
        errorHandler = Objects.requireNonNull(handler, "handler");
    }

    public static int maxChainedCycles() {
        return maxChainedCycles;
    }

    /**
     * Upper bound on cycles run back-to-back for one external dispatch (re-entrant dispatches are
     * queued as follow-up cycles). Exceeding it means a feedback loop and fails with
     * {@link ConduitException}.
     */
    public static void maxChainedCycles(int max) {
        if (max < 1) throw new IllegalArgumentException("max must be >= 1");
        maxChainedCycles = max;
    }

    /** Total handler failures seen since start-up (all policies, both modes). */
    public static long errorCount() {
        return ERROR_COUNT.sum();
    }

    /** Records a failure and notifies the configured handler. Internal use. */
    public static void reportError(Object source, Object event, Throwable error) {
        ERROR_COUNT.increment();
        try {
            errorHandler.onError(source, event, error);
        } catch (Throwable ignored) {
            // an error handler must never break the engine
        }
    }
}
