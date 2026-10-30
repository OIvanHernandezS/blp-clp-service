package com.liverpool.ms_clp_blp.shared.logging;

/**
 * Stable key names for the structured fields attached to log lines via SLF4J's fluent API
 * ({@code addKeyValue}). {@link GcpStructuredLogFormatter} promotes each pair to a top-level JSON
 * field, so these constants <em>are</em> the Cloud Logging query surface
 * ({@code jsonPayload.blockId="blt123"}) and must not be renamed casually — a rename silently breaks
 * every saved query, dashboard and log-based alert built on them.
 *
 * <p>Keys are intentionally flat and camelCase to match the existing MDC labels
 * ({@code requestId}, {@code correlationId}, {@code operation}) emitted by
 * {@code MdcRequestContextFilter}.</p>
 */
public final class LogFields {

    /** Stable event code from {@link LogEvents}; present on every structured line. */
    public static final String EVENT = "event";

    // ── block context ─────────────────────────────────────────────────────────────────────────────
    public static final String BLOCK_ID = "blockId";
    public static final String BLOCK_TYPE = "blockType";
    public static final String SOURCE = "source";
    public static final String REASON = "reason";
    public static final String PRODUCT_COUNT = "productCount";
    public static final String MIN_OF_PRODUCTS = "minOfProducts";
    public static final String FLAG_ID = "flagId";
    /** The identity header a provider needed and the request did not carry. */
    public static final String MISSING_HEADER = "missingHeader";
    /** The request header a {@code conditional_show_hide} rule reads its A/B cohort marker from. */
    public static final String COHORT_HEADER = "cohortHeader";
    /** The cookie a {@code conditional_show_hide} rule reads its A/B cohort marker from. */
    public static final String COHORT_COOKIE = "cohortCookie";

    // ── what the block asked its provider for (one per provider, so each stays queryable) ──────────
    /** Jewel recommendation model. */
    public static final String MODEL = "model";
    /** Vertex category path. */
    public static final String CATEGORY_PATH = "categoryPath";
    /** Salesforce carousel action / campaign. */
    public static final String CAMPAIGN = "campaign";

    // ── composition summary ───────────────────────────────────────────────────────────────────────
    public static final String BLOCKS_TOTAL = "blocksTotal";
    /** Landing page kind: {@code BLP} or {@code CLP}. */
    public static final String PAGE_TYPE = "pageType";
    /** Eager resolutions answered from the per-request memo instead of a second call. */
    public static final String EAGER_DEDUPLICATED = "eagerDeduplicated";
    /** The category a landing page is authored for. */
    public static final String CATEGORY_ID = "categoryId";
    /** The store id carried by the {@code favoriteStore} cookie. */
    public static final String STORE_ID = "storeId";
    /** Products removed from a carousel by the blacklist. */
    public static final String PRODUCTS_REMOVED = "productsRemoved";
    public static final String BLOCKS_SERVED = "blocksServed";
    public static final String BLOCKS_DROPPED = "blocksDropped";
    public static final String BLOCKS_DEGRADED = "blocksDegraded";
    public static final String EAGER_RESOLVED = "eagerResolved";
    public static final String DROPS_BY_REASON = "dropsByReason";

    // ── session context ───────────────────────────────────────────────────────────────────────────
    public static final String BRAND = "brand";
    public static final String LOCALE = "locale";
    public static final String CHANNEL = "channel";
    public static final String AUTHENTICATED = "authenticated";

    // ── failure context ───────────────────────────────────────────────────────────────────────────
    public static final String ERROR_CODE = "errorCode";
    public static final String CATEGORY = "category";
    public static final String RETRYABLE = "retryable";
    public static final String ERROR_CLASS = "errorClass";
    public static final String CAUSE_CHAIN = "causeChain";
    public static final String DETAIL = "detail";
    public static final String URI = "uri";
    /**
     * Whether the rejected request carried an {@code Authorization} header at all. Separates "the
     * caller never sends one" from "the caller sends the wrong one" without ever logging the value.
     */
    public static final String CREDENTIALS_PRESENTED = "credentialsPresented";

    // ── outbound calls ────────────────────────────────────────────────────────────────────────────
    public static final String UPSTREAM = "upstream";
    public static final String UPSTREAM_STATUS = "upstreamStatus";
    /**
     * Which call was made, as Feign's {@code Interface#method(..)} key. Deliberately not named
     * {@code operation}: that key is already an MDC label holding the <em>inbound</em> request line,
     * and one name for both would make the two impossible to tell apart in a query.
     */
    public static final String UPSTREAM_OPERATION = "upstreamOperation";
    public static final String HTTP_STATUS = "httpStatus";
    public static final String HTTP_METHOD = "httpMethod";
    public static final String BREAKER = "breaker";
    /** State a {@link LogEvents#CIRCUIT_BREAKER_STATE_CHANGED} transition left. */
    public static final String BREAKER_FROM_STATE = "breakerFromState";
    /** State a {@link LogEvents#CIRCUIT_BREAKER_STATE_CHANGED} transition entered. */
    public static final String BREAKER_TO_STATE = "breakerToState";
    /**
     * Percentage of failed calls in the breaker's sliding window at the moment it changed state — the
     * field that turns "the breaker opened" into "the breaker opened because 62% of calls failed".
     * Resilience4j reports {@code -1} until the window holds {@code minimumNumberOfCalls}.
     */
    public static final String BREAKER_FAILURE_RATE = "breakerFailureRate";
    /** Failed calls in the breaker's sliding window at the moment it changed state. */
    public static final String BREAKER_FAILED_CALLS = "breakerFailedCalls";
    /** Slow calls in the breaker's sliding window at the moment it changed state. */
    public static final String BREAKER_SLOW_CALLS = "breakerSlowCalls";
    /** Calls the window held in total — the denominator {@link #BREAKER_FAILURE_RATE} is a share of. */
    public static final String BREAKER_BUFFERED_CALLS = "breakerBufferedCalls";
    public static final String ELAPSED_MS = "elapsedMs";
    /** The upstream's own response body, truncated. Present on failures, and on success when asked. */
    public static final String RESPONSE_BODY = "responseBody";
    /**
     * The {@code provider:status} pairs this instance will not treat as failures — neither logged as
     * {@link LogEvents#UPSTREAM_CALL_FAILED} nor written to the audit collection. Emitted once at
     * startup, never per call.
     */
    public static final String EXPECTED_FAILURES = "expectedFailures";

    // ── CMS content resolution ────────────────────────────────────────────────────────────────────
    /** Contentstack content-type uid the call asked for, e.g. {@code page} or {@code global}. */
    public static final String CONTENT_TYPE = "contentType";
    /**
     * Contentstack branch the fallback read from. Present on every direct serve because ms-clp-blp
     * configures the branch separately from the content-service: when the two drift, this field is
     * the only evidence, and it only ever appears during an outage.
     */
    public static final String CMS_BRANCH = "cmsBranch";
    /** Contentstack environment the fallback read from. Same drift argument as {@link #CMS_BRANCH}. */
    public static final String CMS_ENVIRONMENT = "cmsEnvironment";
    /**
     * The Contentstack event a webhook delivery reported, e.g. {@code publish} or {@code unpublish}.
     * Deliberately not {@code event}: that key is the {@link LogEvents} code on every structured line,
     * and one name for both would make the two impossible to tell apart in a query.
     */
    public static final String CMS_EVENT = "cmsEvent";
    /** Contentstack entry uid a webhook delivery was about — what ties a purge back to an editor's save. */
    public static final String ENTRY_UID = "entryUid";

    public static final String BRANDS_TOTAL = "brandsTotal";

    // ── edge-cache purge ──────────────────────────────────────────────────────────────────────────
    /** {@code INVALIDATE} or {@code DELETE} — which of the two an operator actually ran. */
    public static final String PURGE_ACTION = "purgeAction";
    /** {@code STAGING} or {@code PRODUCTION}; the field that separates a rehearsal from the real thing. */
    public static final String PURGE_NETWORK = "purgeNetwork";
    /** {@code URL} or {@code TAG}. */
    public static final String PURGE_TARGET_KIND = "purgeTargetKind";
    /** The paths or tags submitted. Never secret — this is the whole point of the audit line. */
    public static final String PURGE_OBJECTS = "purgeObjects";
    /** How many objects one purge covered; a sudden 0 means the configured list emptied out. */
    public static final String PURGE_OBJECT_COUNT = "purgeObjectCount";
    /** Akamai's own id for the purge — the correlation id to quote back to Akamai. */
    public static final String PURGE_ID = "purgeId";
    /** Akamai's support id, the other half of what their support asks for. */
    public static final String PURGE_SUPPORT_ID = "purgeSupportId";
    /** Akamai's estimate of propagation time; purges are asynchronous, so this is the only ETA. */
    public static final String PURGE_ESTIMATED_SECONDS = "purgeEstimatedSeconds";

    private LogFields() {
    }
}
