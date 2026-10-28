package com.liverpool.ms_clp_blp.clients.contentstackdirect;

/**
 * Why Contentstack cannot serve a brand at all.
 *
 * <p>Logged as {@code reason} on {@code CMS_SOURCE_UNAVAILABLE}. The distinction is operational, and
 * it decides the caller's status code: {@link #UNMAPPED_BRAND} is the caller asking for a brand that
 * is not hosted (a 400, working as designed), while {@link #MISSING_CREDENTIALS} is a deployment that
 * needs fixing (a 502, worth alerting on).</p>
 */
public enum FallbackUnavailableReason {

    /** The brand maps to no Contentstack stack — an unhosted brand such as LVS. */
    UNMAPPED_BRAND,

    /** The brand's stack is known but its api key or delivery token is not configured. */
    MISSING_CREDENTIALS
}
