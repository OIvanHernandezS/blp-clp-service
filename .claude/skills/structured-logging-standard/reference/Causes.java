package com.liverpool.<service>.shared.errors;

/**
 * Renders a throwable's cause chain as one short, greppable string — the answer to "what actually
 * blew up" when a full stack trace is too expensive to emit.
 *
 * <p>Degraded blocks are logged at WARN without a stack trace (they are expected, and a page can
 * degrade several blocks per request), which used to leave only
 * {@code e.getCause().getMessage()} — frequently {@code null}, and never naming the transport-level
 * failure that started it. A chain like
 * {@code DynamicBlockServiceUnavailableException <- FeignException$GatewayTimeout <- SocketTimeoutException}
 * distinguishes a timeout from a 500 from a DNS failure at a glance, at a fraction of the ingestion
 * cost of a stack trace.</p>
 */
public final class Causes {

    private static final String ARROW = " <- ";

    /** Guards against a self-referential or absurdly deep chain; nothing legitimate nests this far. */
    private static final int MAX_DEPTH = 8;

    private Causes() {
    }

    /**
     * @param throwable the error to describe (may be {@code null})
     * @return simple class names from outermost to root cause, or {@code null} if there is nothing
     *         to describe — a {@code null} value is skipped by the log formatter rather than
     *         emitting an empty field
     */
    public static String chain(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        StringBuilder chain = new StringBuilder(throwable.getClass().getSimpleName());
        Throwable current = throwable.getCause();
        for (int depth = 0; current != null && depth < MAX_DEPTH && current != current.getCause(); depth++) {
            chain.append(ARROW).append(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return chain.toString();
    }

    /**
     * @param throwable the error to describe (may be {@code null})
     * @return the deepest cause's message, falling back to the outermost one — the message that
     *         names the failing host/port or the upstream body is usually at the root, and it is
     *         the one lost by logging only {@code getCause().getMessage()}
     */
    public static String rootMessage(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        for (int depth = 0; current.getCause() != null && depth < MAX_DEPTH
                && current.getCause() != current; depth++) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : throwable.getMessage();
    }
}
