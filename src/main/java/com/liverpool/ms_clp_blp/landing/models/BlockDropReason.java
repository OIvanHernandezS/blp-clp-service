package com.liverpool.ms_clp_blp.landing.models;

/**
 * Why a block the CMS asked for did not reach the response, or reached it carrying nothing.
 *
 * <p>Every one of these decisions used to be invisible: composition dropped blocks with a bare
 * {@code continue} and resolvers with a bare {@code return null}, so "the banner is missing in
 * production" could only be answered by reading the code and guessing which of eight rules fired.
 * Naming each reason turns that into one query
 * ({@code jsonPayload.event="BLOCK_DROPPED" AND jsonPayload.blockId="…"}).</p>
 *
 * <p>A block skipped before its upstream call is reported here too: a skip always ends as an empty
 * resolution and therefore as a drop, and splitting the vocabulary would only mean two dashboards
 * for one question. For the same reason an outright <em>failure</em> is named here as well, so the
 * question "why is this block not on the page?" has exactly one vocabulary whatever the answer.</p>
 *
 * <p><strong>This enum is a public API contract</strong> (ADR-043), not only a log vocabulary: the
 * {@code /blp|clp/blocks/*} endpoints return the value alongside a non-{@code AVAILABLE}
 * {@link DynamicBlockStatus}, which is how the frontend tells "legitimately empty, drop the block" from
 * "retry me later". Changes are therefore <strong>additive only</strong> — renaming a constant breaks
 * live clients as well as every saved Cloud Logging query.</p>
 */
public enum BlockDropReason {

    /** {@code audience_filter} does not match the session's authentication state. */
    AUDIENCE_MISMATCH,

    /** {@code enable_on_web}/{@code enable_on_apps} is false for the requesting channel. */
    CHANNEL_DISABLED,

    /** A {@code countdown} that is inactive or whose timer already elapsed. */
    COUNTDOWN_EXPIRED,

    /** A {@code container_guest} on an authenticated session. */
    GUEST_ONLY_FOR_AUTHENTICATED,

    /** The block's feature flag is off and this block type is dropped rather than disabled. */
    FEATURE_FLAG_OFF,

    /** The resolver ran and chose to drop the block (empty resolution, audience the provider skips). */
    RESOLVER_RETURNED_EMPTY,

    /** Fewer products came back than the block's {@code min_of_products} threshold. */
    BELOW_MIN_PRODUCTS,

    /** No user identity on the session, and the provider personalises per user. */
    MISSING_USER_IDENTITY,

    /** No device identity on the session, and the provider requires one. */
    MISSING_DEVICE_IDENTITY,

    /** The CMS block carries no provider model/algorithm to query. */
    MISSING_MODEL_CONFIG,

    /** The CMS block carries no category to query. */
    MISSING_CATEGORY,

    /** The CMS block carries no campaign to query. */
    MISSING_CAMPAIGN,

    /** A recommendation that needs an anchor product, on a page that has none (no PDP context). */
    MISSING_ANCHOR_PRODUCT,

    /** The upstream answered successfully but carried no usable products. */
    UPSTREAM_RETURNED_NO_PRODUCTS,

    /**
     * The backing service errored, so the block degraded rather than resolved. Pairs with
     * {@link DynamicBlockStatus#UNAVAILABLE} — unlike every reason above it, this one is worth retrying.
     */
    UPSTREAM_FAILED,

    /**
     * A circuit breaker denied the call before it left the process. Also pairs with
     * {@link DynamicBlockStatus#UNAVAILABLE}, and is retryable once the breaker closes.
     */
    CIRCUIT_BREAKER_OPEN,

    /**
     * A {@code products_list} whose {@code source_of_data} this service does not implement
     * ({@code lob}, {@code recently_viewed}). Routing it to whichever provider shares
     * its content type is the misrouting bug ADR-021 fixed, so it is reported instead.
     */
    UNSUPPORTED_SOURCE_OF_DATA,

    /**
     * The block's active {@code conditional_show_hide} rule excludes this shopper's A/B cohort: a
     * {@code show} rule and a marker of {@code false}, or a {@code hide} rule and a marker of
     * {@code true}. The other arm of the experiment is being served.
     */
    CONDITIONAL_SHOW_HIDE_EXCLUDED,

    /**
     * The block's active {@code conditional_show_hide} rule could not place the shopper in a cohort:
     * the named header or cookie was not sent, or carried something other than {@code true}/{@code false}.
     * Dropped whatever the rule's action, because the wrong arm is worse than no carousel.
     */
    CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN
}
