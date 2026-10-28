package com.liverpool.ms_clp_blp.landing;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.PageType;

/**
 * One {@link LandingCompositionService} per {@link PageType}.
 *
 * <p>The two page kinds compose identically; they differ only in the endpoint a lazy carousel
 * advertises — a BLP block points at {@code /blp/blocks/products-list}, a CLP block at
 * {@code /clp/blocks/products-list} — and that endpoint is baked into each service's
 * {@code BlockResolutionCatalog}. Keeping one service per kind keeps the composition core unaware of
 * page kinds at all.</p>
 */
public final class LandingCompositions {

    private final Map<PageType, LandingCompositionService> byType;

    /**
     * @param byType a composition service for every page type
     * @throws IllegalStateException when a page type has no service, which would fail every request for
     *                               it at runtime instead of at startup
     */
    public LandingCompositions(Map<PageType, LandingCompositionService> byType) {
        Map<PageType, LandingCompositionService> copy = new EnumMap<>(PageType.class);
        copy.putAll(byType);
        for (PageType type : PageType.values()) {
            if (copy.get(type) == null) {
                throw new IllegalStateException("No LandingCompositionService configured for " + type);
            }
        }
        this.byType = Collections.unmodifiableMap(copy);
    }

    /**
     * @param pageType the page kind being composed
     * @return the composition service for it
     */
    public LandingCompositionService forPage(PageType pageType) {
        return byType.get(pageType);
    }
}
