package com.liverpool.ms_clp_blp.shared.logging;

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

    /** One-per-request summary of a BLP/CLP composition. The only INFO on the hot path. */
    LANDING_COMPOSED(Level.INFO),

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

    /**
     * A block inside the eager offset was served as a lazy placeholder instead, because its content
     * differs per A/B cohort and the landing page is shared at the edge. The answer to "why does this
     * above-the-fold carousel load late".
     */
    BLOCK_DEFERRED(Level.DEBUG),

    /** A CMS configuration entry (per-brand flags) was read and served. */
    CONTENT_SERVED(Level.INFO),

    /** An outbound call is about to go on the wire; the message is the equivalent {@code curl}. */
    UPSTREAM_CALL_STARTED(Level.DEBUG),

    /** An outbound call returned a non-2xx status or failed at the transport level. */
    UPSTREAM_CALL_FAILED(Level.WARN),

    /** An outbound call succeeded. */
    UPSTREAM_CALL_OK(Level.DEBUG),

    /** The upstream answered 2xx but carried no usable products. */
    UPSTREAM_NO_PRODUCTS(Level.DEBUG),

    /**
     * Startup summary of the outbound outcomes this instance will not treat as failures (ADR-062).
     * INFO: a suppression list nobody can see is how errors get lost quietly, so the configuration
     * must survive a production log level rather than being something an incident has to infer.
     */
    OUTBOUND_EXPECTED_FAILURES_LOADED(Level.INFO),

    /** A circuit breaker denied the call before it left the process. */
    CIRCUIT_BREAKER_OPEN(Level.WARN),

    /**
     * A circuit breaker transitioned between states (e.g. CLOSED to OPEN, HALF_OPEN to CLOSED). Fired
     * once per transition, not once per call — {@link #CIRCUIT_BREAKER_OPEN} already covers the
     * per-rejected-call signal; this one is the timestamped fact a post-mortem needs to say exactly
     * when a breaker opened, how many times it flapped, and when it recovered.
     */
    CIRCUIT_BREAKER_STATE_CHANGED(Level.WARN),

    /**
     * Contentstack answered a CMS read, recording which branch and environment served it. DEBUG, not
     * INFO: this was the fallback's "it earned its keep" line when the content-service proxy fronted
     * every read (ADR-046), so it fired only during an outage. With Contentstack as the only live
     * source (ADR-053) it fires on every cache miss instead, and an INFO line per read is a cloud-cost
     * problem, not observability. The per-request INFO summary already reports the origin.
     */
    CMS_SOURCE_SERVED(Level.DEBUG),

    /**
     * Contentstack cannot serve a brand at all — it maps to no stack, or its stack's credentials are
     * not configured. WARN, and always with {@code reason}: no read is attempted, so the caller gets a
     * 400 or a 502 with nothing upstream to explain it.
     */
    CMS_SOURCE_UNAVAILABLE(Level.WARN),

    /**
     * The category indexer could not answer for this page's category. WARN, never fatal: the page is
     * served without {@code categoryData}, so a run of these explains landing pages with no
     * breadcrumb or sub-category chips.
     */
    CATEGORY_UNAVAILABLE(Level.WARN),

    /** The CMS redirect lookup for a category failed; the page is served without one. */
    CATEGORY_REDIRECT_UNAVAILABLE(Level.WARN),

    /**
     * The favourite store named by the {@code favoriteStore} cookie could not be resolved; the
     * {@code selected_store.*} analytics values are left unset. DEBUG: a stale cookie is ordinary.
     */
    FAVORITE_STORE_UNAVAILABLE(Level.DEBUG),

    /**
     * The product blacklist could not be read; carousels are served unfiltered (fail-open). WARN
     * because, while it lasts, restricted products can reach the storefront.
     */
    RESTRICTED_PRODUCTS_UNAVAILABLE(Level.WARN),

    /** Blacklisted products were removed from a carousel. Carries how many. */
    RESTRICTED_PRODUCTS_REMOVED(Level.DEBUG),

    /** A resolver's payload key collided with a CMS field; the CMS value was kept. */
    PAYLOAD_KEY_COLLISION(Level.WARN),

    /** A feature flag was evaluated. */
    FEATURE_FLAG_EVALUATED(Level.DEBUG),

    /** A numeric runtime setting was evaluated. */
    RUNTIME_SETTING_EVALUATED(Level.DEBUG),

    /**
     * A brand's flags were captured from the CMS and written to the store. INFO because it is the one
     * line that says a configuration change actually took effect, and it is rare by nature.
     */
    BRAND_FLAGS_SYNCED(Level.INFO),

    /** A webhook arrived for something this service does not configure; nothing was written. */
    BRAND_FLAGS_SYNC_IGNORED(Level.DEBUG),

    /** A webhook was for us but could not be applied; the previous configuration still stands. */
    BRAND_FLAGS_SYNC_FAILED(Level.WARN),

    /** An instance refreshed its copy of a brand's flags from the store. */
    BRAND_FLAGS_CACHE_REFRESHED(Level.DEBUG),

    /**
     * A brand had no document at startup, so one was written with the current
     * {@code landing.feature-flags} snapshot. INFO because it is rare and worth seeing — most brands
     * should already have a document after the first boot of the fleet.
     */
    BRAND_FLAGS_SEEDED(Level.INFO),

    /** A brand's default snapshot could not be written. WARN: it is retried on every future boot. */
    BRAND_FLAGS_SEED_FAILED(Level.WARN),

    /** Packaged per-brand baselines were read at startup. One line, at boot, so it stays DEBUG. */
    BRAND_FLAGS_DEFAULTS_LOADED(Level.DEBUG),

    /**
     * A packaged per-brand baseline file could not be parsed and was skipped. WARN because that brand
     * silently loses its baseline while every other brand keeps working — easy to miss otherwise.
     */
    BRAND_FLAGS_DEFAULTS_UNREADABLE(Level.WARN),

    /**
     * The per-brand flag store could not be reached, so flags fell back to their configured defaults.
     * WARN, not ERROR: the page is still being served correctly, just not with per-brand overrides.
     */
    BRAND_FLAGS_STORE_UNAVAILABLE(Level.WARN),

    /**
     * An edge-cache purge is about to be submitted to the CDN. INFO, unlike the DEBUG-level
     * {@code UPSTREAM_CALL_STARTED}: a purge is rare, operator-triggered and changes what the whole
     * internet sees, so the record that someone asked for one must survive a production log level.
     */
    CACHE_PURGE_REQUESTED(Level.INFO),

    /** The CDN accepted a purge. Carries its {@code purgeId}/{@code supportId} correlation ids. */
    CACHE_PURGE_SUCCEEDED(Level.INFO),

    /**
     * A purge was rejected, unreachable, timed out, or came back unusable. WARN rather than ERROR:
     * nothing a user is waiting on has failed — the edge simply still holds the old content, and the
     * operator gets the failure synchronously in the response.
     */
    CACHE_PURGE_FAILED(Level.WARN),

    /**
     * A Contentstack publish caused an edge-cache purge. INFO for the same reason as
     * {@link #CACHE_PURGE_REQUESTED}: it changes what the whole internet sees, and it is the one line
     * that ties an editor's save to the invalidation it produced.
     */
    CMS_PURGE_TRIGGERED(Level.INFO),

    /**
     * A Contentstack delivery arrived and purged nothing. DEBUG and always with {@code reason}: a stack
     * configured to notify this service about every content type makes this the majority of the
     * webhook's traffic, and an INFO line per irrelevant delivery is a cloud-cost problem.
     */
    CMS_PURGE_IGNORED(Level.DEBUG),

    /**
     * A publish was relevant and the purge could not be submitted. WARN: the page is still served, the
     * edge simply keeps the old content until its TTL expires — and unlike an operator's purge, nobody
     * is watching a response, so this line is the only notification there is.
     */
    CMS_PURGE_FAILED(Level.WARN),

    /** A typed domain error reached the HTTP boundary. */
    DOMAIN_ERROR(Level.WARN),

    /** Request rejected by validation at the HTTP boundary. */
    VALIDATION_FAILED(Level.WARN),

    /**
     * Request rejected at the HTTP Basic boundary, before any controller ran. A sustained stream of
     * these means a consumer is deployed without its credential, not that the service is under attack.
     */
    AUTHENTICATION_FAILED(Level.WARN),

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
