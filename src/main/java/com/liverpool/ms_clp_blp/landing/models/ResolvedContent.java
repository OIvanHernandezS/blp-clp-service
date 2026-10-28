package com.liverpool.ms_clp_blp.landing.models;

/**
 * Opaque, already-serialisable detail produced by a {@link com.liverpool.ms_clp_blp.landing.ports.BlockResolver}
 * when a dynamic block is resolved eagerly.
 *
 * <p>The core stays agnostic of each block's response shape: a resolver (living in the block
 * sub-slice) maps its own {@code *Resolution} to whatever DTO the block's dedicated endpoint would
 * return and wraps it here. The payload is carried through to the REST layer and serialised as-is,
 * so the eager inline detail is byte-for-byte identical to the lazy endpoint's response.</p>
 *
 * @param payload the resolved detail, ready for JSON serialisation (never {@code null})
 */
public record ResolvedContent(Object payload) {

    public ResolvedContent {
        if (payload == null) {
            throw new IllegalArgumentException("ResolvedContent payload must not be null");
        }
    }
}
