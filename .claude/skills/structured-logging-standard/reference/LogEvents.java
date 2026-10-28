package com.liverpool.<service>.shared.logging;

import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.slf4j.spi.NOPLoggingEventBuilder;

/**
 * Catalogue of the operational events this service emits, each pinned to the level it deserves.
 *
 * <p>Two problems this solves. First, the level stops being whatever the author of the line felt
 * like at the time: "no Jewel model configured" and "no Vertex category configured" are the same
 * class of business no-op and now log identically. Second, every line carries a stable
 * {@code event} code, so Cloud Logging can filter and alert on the event
 * ({@code jsonPayload.event="UPSTREAM_CALL_FAILED"}) instead of regex-matching a prose message that
 * anyone may reword.</p>
 *
 * <p>Use {@link #at(Logger, LogEvents)}; when the level is disabled SLF4J returns a NOP builder, so
 * neither the key-values nor the message are ever evaluated — no manual {@code isDebugEnabled()}
 * guards needed.</p>
 */
public enum LogEvents {

    /** One-per-request summary of a {@code GET /home} composition. The only INFO on the hot path. */
    HOME_COMPOSED(Level.INFO),

    /** A block was not served. Always carries {@code reason}. */
    BLOCK_DROPPED(Level.DEBUG),

    /** Resolution failed; the block degraded to an UNAVAILABLE placeholder (or to static content). */
    BLOCK_DEGRADED(Level.WARN),

    /** No adapter owns this (type, source) pair — the block is served as static content. */
    BLOCK_NO_RESOLVER(Level.WARN),

    /** The upstream call was not attempted because the session/config could not satisfy it. */
    BLOCK_SKIPPED(Level.DEBUG),

    /** A block resolved successfully. */
    BLOCK_RESOLVED(Level.DEBUG),

    /** A singleton content endpoint ({@code /loyalty}, {@code /global-data}) served a response. */
    CONTENT_SERVED(Level.INFO),

    /** An outbound call is about to go on the wire; the message is the equivalent {@code curl}. */
    UPSTREAM_CALL_STARTED(Level.DEBUG),

    /** An outbound call returned a non-2xx status or failed at the transport level. */
    UPSTREAM_CALL_FAILED(Level.WARN),

    /** An outbound call succeeded. */
    UPSTREAM_CALL_OK(Level.DEBUG),

    /** The upstream answered 2xx but carried no usable products. */
    UPSTREAM_NO_PRODUCTS(Level.DEBUG),

    /** A circuit breaker denied the call before it left the process. */
    CIRCUIT_BREAKER_OPEN(Level.WARN),

    /** Token exchange failed; the request continues as a guest session. */
    SESSION_DEGRADED_TO_GUEST(Level.WARN),

    /** The CMS answered, but the definition is missing something the page is built from. */
    CMS_DEFINITION_INCOMPLETE(Level.WARN),

    /** A resolver's payload key collided with a CMS field; the CMS value was kept. */
    PAYLOAD_KEY_COLLISION(Level.WARN),

    /** A feature flag was evaluated. */
    FEATURE_FLAG_EVALUATED(Level.DEBUG),

    /** A typed domain error reached the HTTP boundary. */
    DOMAIN_ERROR(Level.WARN),

    /** Request rejected by validation at the HTTP boundary. */
    VALIDATION_FAILED(Level.WARN),

    /** Something we did not model blew up — always accompanied by a stack trace. */
    UNEXPECTED_ERROR(Level.ERROR);

    private final Level level;

    LogEvents(Level level) {
        this.level = level;
    }

    /**
     * @return the level this event is always logged at
     */
    public Level level() {
        return level;
    }

    /**
     * Starts a structured log line for this event at its catalogued level.
     *
     * @param log   the caller's logger
     * @param event the event being reported
     * @return a builder already carrying {@code event=<code>}; a NOP builder if the level is off
     */
    public static LoggingEventBuilder at(Logger log, LogEvents event) {
        return at(log, event, event.level);
    }

    /**
     * Starts a structured log line for this event at an overridden level. Reserved for the case
     * where the same event is materially more serious in one call site — notably a degradation
     * whose cause is an untyped exception, which is a bug in this service rather than an upstream
     * failure.
     *
     * @param log   the caller's logger
     * @param event the event being reported
     * @param level the level to use instead of the catalogued one
     * @return a builder already carrying {@code event=<code>}; a NOP builder if the level is off
     */
    public static LoggingEventBuilder at(Logger log, LogEvents event, Level level) {
        LoggingEventBuilder builder = log.atLevel(level);
        // A disabled level already discards everything, so it is returned unwrapped: no allocation,
        // and no work for a line that will not be emitted.
        if (builder == NOPLoggingEventBuilder.singleton()) {
            return builder;
        }
        return new NullSafeEventBuilder(builder).addKeyValue(LogFields.EVENT, event.name());
    }
}
