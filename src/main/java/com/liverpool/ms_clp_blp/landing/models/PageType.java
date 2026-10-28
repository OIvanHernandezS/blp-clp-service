package com.liverpool.ms_clp_blp.landing.models;

import java.util.Locale;
import java.util.Set;

/**
 * The two landing-page kinds this service composes, and everything that differs between them.
 *
 * <p>Both are Contentstack page entries looked up by {@code category_id} — the last segment of the
 * storefront URL — rather than by {@code url}, which is why {@code /tienda/esteelauder/catst26617625}
 * resolves the entry whose authored url is {@code /tienda/estee-lauder/catst26617625} (Rule 19
 * reference: content-service {@code getCategoryIdFromSlug}). They differ only in content type, the
 * public route prefix and the app-only keys a BLP strips (digital_bff {@code deleteKeysFrom}).</p>
 */
public enum PageType {

    /** Brand landing page — Contentstack content type {@code page_blp}. */
    BLP("page_blp", "blp", Set.of("apps_top_content", "apps_products", "apps_article")),

    /** Category landing page — Contentstack content type {@code page_clp}. */
    CLP("page_clp", "clp", Set.of());

    private final String contentTypeUid;
    private final String routeSegment;
    private final Set<String> strippedKeys;

    PageType(String contentTypeUid, String routeSegment, Set<String> strippedKeys) {
        this.contentTypeUid = contentTypeUid;
        this.routeSegment = routeSegment;
        this.strippedKeys = strippedKeys;
    }

    /** @return the Contentstack content type holding this kind of page */
    public String contentTypeUid() {
        return contentTypeUid;
    }

    /** @return the first path segment of this kind's public routes ({@code blp} / {@code clp}) */
    public String routeSegment() {
        return routeSegment;
    }

    /**
     * @return entry keys removed from the response for this kind — the app-only sections of a BLP,
     *         which the web never renders (digital_bff {@code deleteKeysFrom[PAGE_BLP]})
     */
    public Set<String> strippedKeys() {
        return strippedKeys;
    }

    /**
     * Resolves a route segment back to its page type.
     *
     * @param routeSegment {@code blp} or {@code clp}, any case
     * @return the matching type
     * @throws IllegalArgumentException when the segment names neither
     */
    public static PageType fromRouteSegment(String routeSegment) {
        String normalized = routeSegment == null ? "" : routeSegment.trim().toLowerCase(Locale.ROOT);
        for (PageType type : values()) {
            if (type.routeSegment.equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown landing page type: " + routeSegment);
    }
}
