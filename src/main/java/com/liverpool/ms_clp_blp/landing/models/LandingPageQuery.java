package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;

import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Inbound request for composing a landing page.
 *
 * @param pageType            BLP or CLP
 * @param locale              content locale (e.g. {@code es-mx})
 * @param path                the storefront path after the locale (e.g. {@code tienda/esteelauder/catst26617625});
 *                            only its last segment selects the entry
 * @param session             resolved session context (login/guest, brand, channel)
 * @param gatewayHeaders      propagated {@code x-*} request headers, lower-cased (identity for eager
 *                            personalisation); never {@code null}
 * @param eagerOffsetOverride optional per-request eager offset; {@code null} uses the configured default
 * @param favoriteStoreId     the {@code favoriteStore} cookie, or {@code null}; feeds the
 *                            {@code selected_store.*} analytics values only
 */
public record LandingPageQuery(
        PageType pageType,
        String locale,
        String path,
        SessionContext session,
        Map<String, String> gatewayHeaders,
        Integer eagerOffsetOverride,
        String favoriteStoreId) {

    private static final String PATH_SEPARATOR = "/";

    public LandingPageQuery {
        gatewayHeaders = gatewayHeaders == null ? Map.of() : Map.copyOf(gatewayHeaders);
    }

    /**
     * The category id the page is looked up by: the last non-empty segment of {@link #path}, exactly as
     * typed (digital_bff {@code id?.split('/').pop()} — never normalised, since the CMS field is
     * authored lower-case and the indexer accepts either).
     *
     * @return the category id, or {@code null} when the path has no segment at all
     */
    public String categoryId() {
        if (path == null) {
            return null;
        }
        String[] segments = path.split(PATH_SEPARATOR);
        for (int i = segments.length - 1; i >= 0; i--) {
            if (!segments[i].isBlank()) {
                return segments[i].trim();
            }
        }
        return null;
    }
}
