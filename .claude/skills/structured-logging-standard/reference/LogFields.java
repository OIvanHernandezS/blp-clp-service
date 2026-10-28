package com.liverpool.<service>.shared.logging;

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

    // ── what the block asked its provider for (one per provider, so each stays queryable) ──────────
    /** Jewel recommendation model. */
    public static final String MODEL = "model";
    /** Vertex category path. */
    public static final String CATEGORY_PATH = "categoryPath";
    /** Salesforce carousel action / campaign. */
    public static final String CAMPAIGN = "campaign";

    // ── composition summary ───────────────────────────────────────────────────────────────────────
    public static final String BLOCKS_TOTAL = "blocksTotal";
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
    public static final String ELAPSED_MS = "elapsedMs";
    /** The upstream's own response body, truncated. Present on failures, and on success when asked. */
    public static final String RESPONSE_BODY = "responseBody";

    private LogFields() {
    }
}
