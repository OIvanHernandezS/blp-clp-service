package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Stable, machine-readable error codes surfaced to API consumers (Rule 20 — no literal strings).
 *
 * <p>The three dynamic-block signals are intentionally distinct so the frontend can tell them apart:
 * {@link #BLOCK_DISABLED} (runtime off) vs {@link #BLOCK_SERVICE_UNAVAILABLE} (backing service down)
 * vs {@link #SERVICE_UNAVAILABLE} (circuit breaker open).</p>
 *
 * <p>The content-service signals are split the same way, because "this brand has no content published"
 * and "the CMS is down" demand opposite reactions: {@link #BRAND_NOT_SUPPORTED} (400 — the caller sent
 * a brand the CMS does not host) vs {@link #LANDING_PAGE_NOT_FOUND} (404 — brand is valid, no page is
 * published for that category) vs {@link #CONTENT_SERVICE_UNAVAILABLE} (502 — Contentstack failed).
 * Only the last one is worth
 * retrying.</p>
 */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    /** Inbound HTTP Basic credential missing or wrong; emitted by {@code BasicAuthEntryPoint}. */
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String BRAND_NOT_SUPPORTED = "BRAND_NOT_SUPPORTED";
    public static final String LANDING_PAGE_NOT_FOUND = "LANDING_PAGE_NOT_FOUND";
    public static final String CONTENT_SERVICE_UNAVAILABLE = "CONTENT_SERVICE_UNAVAILABLE";
    public static final String BLOCK_DISABLED = "BLOCK_DISABLED";
    public static final String BLOCK_SERVICE_UNAVAILABLE = "BLOCK_SERVICE_UNAVAILABLE";
    public static final String SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    public static final String CONFIGURATION_ERROR = "CONFIGURATION_ERROR";
    public static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";
    /** {@code POST /ops/cache-purge**} called while {@code cache-purge.enabled} is false. */
    public static final String CACHE_PURGE_DISABLED = "CACHE_PURGE_DISABLED";
    /**
     * A purge was requested but the Akamai EdgeGrid credential is incomplete. Distinct from
     * {@link #CACHE_PURGE_FAILED} on purpose: nothing was sent, and the fix is a deployment variable
     * rather than anything to do with Akamai.
     */
    public static final String CACHE_PURGE_NOT_CONFIGURED = "CACHE_PURGE_NOT_CONFIGURED";
    /** Akamai rejected the purge, was unreachable, timed out, or answered something unusable. */
    public static final String CACHE_PURGE_FAILED = "CACHE_PURGE_FAILED";

    private ErrorCodes() {
    }
}
