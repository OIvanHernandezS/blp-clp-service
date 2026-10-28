package com.liverpool.ms_clp_blp.landing.models;

/**
 * Whether a dynamic block can be resolved, and — once it has been — whether it produced anything. These
 * states must never collapse into one another: each one implies a different action, and telling the
 * frontend the wrong one is how a block ends up retried forever or silently missing (ADR-043).
 *
 * <p>Two contracts share this enum, and they use <strong>disjoint</strong> subsets of it. Read the
 * value against the response it arrived in:</p>
 *
 * <ul>
 *   <li><strong>A {@link DynamicPlaceholder} on {@code GET /blp|clp/**}</strong> — answers "should I call the
 *       resolution endpoint?" — is {@link #AVAILABLE}, {@link #DISABLED} or {@link #UNAVAILABLE}. It is
 *       never {@link #EMPTY}: nothing has been resolved yet, so there is nothing to be empty of.</li>
 *   <li><strong>A {@code /blp|clp/blocks/*} resolution response</strong> — answers "did I get something to
 *       draw?" — is {@link #AVAILABLE}, {@link #EMPTY} or {@link #UNAVAILABLE}. It is never
 *       {@link #DISABLED}: that value answers the placeholder's question, not this one. A caller
 *       <em>can</em> arrive here with a disabled block — a {@link #DISABLED} placeholder still carries
 *       its resolution url, so a frontend that ignores the status will call the endpoint anyway — and
 *       the use case answers {@link #EMPTY} with {@link BlockDropReason#FEATURE_FLAG_OFF} rather than
 *       echoing {@link #DISABLED} back.</li>
 * </ul>
 *
 * <p>Whenever the status is not {@link #AVAILABLE}, the response also carries a {@link BlockDropReason}
 * naming the rule that fired.</p>
 */
public enum DynamicBlockStatus {

    /**
     * Placeholder: the block is enabled; the frontend should call its resolution endpoint.
     * Resolution response: resolved, and there is content to draw.
     */
    AVAILABLE,

    /** The block is intentionally turned off at runtime (feature flag); not an error — skip it. */
    DISABLED,

    /**
     * Resolution succeeded but produced nothing renderable — a guest session the provider cannot
     * personalise, a carousel below its {@code min_of_products}, an upstream that answered 2xx carrying
     * no products.
     *
     * <p><strong>Terminal: the frontend drops the block and must not retry.</strong> This is the state
     * that used to be indistinguishable from {@link #AVAILABLE} — both came back with no content, so a
     * legitimately empty carousel read as a transient failure and was retried.</p>
     */
    EMPTY,

    /**
     * The block's backing service failed or its circuit breaker is open; render the fallback instead.
     * Unlike {@link #EMPTY} this <em>is</em> worth retrying later.
     */
    UNAVAILABLE
}
