package com.liverpool.ms_clp_blp.shared.web;

import org.springframework.http.ResponseEntity;

/**
 * Per-request side channel carrying which {@link ContentOrigin} answered the current CMS-backed
 * request, from the few adapter classes that know (the content-service router, the Mongo backup
 * tier, the Caffeine cache) to the controller that builds the response.
 *
 * <p>A plain {@link ThreadLocal} rather than a Spring bean: the whole synchronous read path for
 * the landing page entry runs on one thread per request (virtual
 * threads included — see {@code CmsOriginContextFilter}), so there is no hand-off to guard against.
 * Best-effort observability metadata, not a correctness-critical value.</p>
 */
public final class CmsResponseMetadataContext {

    static final String HEADER_DATA_ORIGIN = "x-data-origin";

    private static final ThreadLocal<ContentOrigin> CURRENT = new ThreadLocal<>();

    private CmsResponseMetadataContext() {
    }

    /**
     * Records which source answered the request in progress. Callable more than once per request —
     * later tiers (e.g. the Mongo backup, consulted only after the router already ran) correctly
     * overwrite an earlier value with the one that actually served the response.
     *
     * @param origin the source that answered
     */
    public static void set(ContentOrigin origin) {
        CURRENT.set(origin);
    }

    /**
     * @return the origin recorded for the request in progress, if any was set
     */
    public static ContentOrigin current() {
        return CURRENT.get();
    }

    /**
     * Clears the recorded origin. Must run after every request ({@code CmsOriginContextFilter}'s
     * {@code finally}) so a value never leaks into a later request on a reused thread.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Adds {@code x-data-origin} to {@code builder} when an origin was recorded for this request;
     * leaves the builder untouched otherwise (e.g. a controller test that never populated the
     * context, or a code path this ThreadLocal was never wired into).
     *
     * @param builder the in-progress response builder
     * @param <B>     the concrete builder type, so the fluent chain keeps its type
     * @return {@code builder}, for chaining
     */
    public static <B extends ResponseEntity.HeadersBuilder<B>> B attachTo(B builder) {
        ContentOrigin origin = current();
        if (origin != null) {
            builder.header(HEADER_DATA_ORIGIN, origin.headerValue());
        }
        return builder;
    }
}
