package io.lightning.conduit;

/**
 * What the engine does when a handler throws.
 */
public enum ErrorPolicy {
    /**
     * Abort the current cycle (pending work is discarded) and rethrow the failure to the
     * caller of the external {@code dispatch} as a {@link ConduitException}. The graph stays
     * usable for later events. This is the default.
     */
    HALT,
    /**
     * Skip the failed event: anything the failing handler dispatched is discarded, the rest of
     * the cycle continues. The failure is reported to the {@link ErrorHandler}; nothing is logged.
     */
    SKIP_EVENT,
    /**
     * Log at WARN, report to the {@link ErrorHandler}, and continue as if the handler had
     * returned normally (outputs it dispatched before failing are kept).
     */
    LOG_CONTINUE
}
