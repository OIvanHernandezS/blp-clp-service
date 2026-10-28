package com.liverpool.ms_clp_blp.landing.models;

/**
 * How a dynamic block's detail is delivered to the frontend, decided at composition time by the
 * block's position relative to the configured eager-resolution offset.
 *
 * <p>Blocks above the fold (index {@code < offset}) are resolved {@link #EAGER}ly — the backing
 * service is called during {@code GET /blp|clp/**} and the detail is inlined — to avoid first-paint
 * flickering. Blocks below the fold are {@link #LAZY}: {@code GET /blp|clp/**} advertises the resolution
 * endpoint (url + http method) and the frontend fetches each one independently.</p>
 */
public enum LoadStrategy {

    /** Detail resolved during the page request and inlined in the response. */
    EAGER,

    /** Detail deferred; the response advertises the endpoint the frontend must call. */
    LAZY
}
