package com.liverpool.ms_clp_blp.shared.web;

import org.springframework.http.CacheControl;

/**
 * The two {@code Cache-Control} policies this service emits on its edge-facing GETs, and the only
 * place their directives are spelled out.
 *
 * <p><strong>The origin declares cacheability; Akamai owns the lifetime.</strong> {@link #SHARED}
 * deliberately carries no {@code max-age}: the edge TTL comes from the property's {@code caching}
 * behavior and the browser's from {@code downstreamCache}, so changing a caching window is a property
 * edit and never a redeploy. Do not "fix" the missing {@code max-age} by adding one — that moves TTL
 * authority back into the backend, which is exactly what this design gives up.</p>
 *
 * <p>{@code public} is not decoration. Every request to this service carries HTTP Basic
 * {@code Authorization}, and a shared cache may not reuse a response to such a request unless the
 * response says it may (RFC 9111 §3.5). Without it the edge reports {@code x-check-cacheable: NO}
 * however the property is configured.</p>
 *
 * <p>Emitting either policy has a second, load-bearing effect: Spring Security's
 * {@code CacheControlHeadersWriter} skips a response that already carries {@code Cache-Control}, so
 * setting one here replaces {@code no-cache, no-store, max-age=0, must-revalidate} <em>and</em>
 * suppresses the {@code Pragma}/{@code Expires} pair that would block the edge on their own. That is
 * why the policy belongs on the {@code ResponseEntity} rather than in a filter: an error response
 * never reaches the controller's builder, so it keeps the security default and stays uncacheable,
 * which is what we want.</p>
 */
public final class EdgeCacheControl {

    private EdgeCacheControl() {
    }

    /**
     * Cacheable by the shared edge. Renders as {@code public} — no lifetime, by design (see the class
     * note).
     */
    public static final CacheControl SHARED = CacheControl.empty().cachePublic();

    /**
     * Never stored by any cache, shared or private. Renders as {@code no-store, private}.
     *
     * <p>{@code private} is not redundant next to {@code no-store}: it is the directive that keeps the
     * response out of the browser's own store, which matters because {@link #SHARED} carries no
     * {@code max-age} and browsers may cache such a response heuristically.</p>
     */
    public static final CacheControl PERSONAL = CacheControl.noStore().cachePrivate();
}
