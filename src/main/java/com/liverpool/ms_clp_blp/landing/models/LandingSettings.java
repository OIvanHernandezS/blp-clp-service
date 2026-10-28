package com.liverpool.ms_clp_blp.landing.models;

import java.util.Set;

/**
 * The complete set of numeric runtime settings ms-clp-blp recognises — the counterpart of
 * {@link LandingFeatureFlags} for values that are a number rather than an on/off decision.
 *
 * <p>A setting is overridable per brand in the same store as the flags: the {@code settings} map of
 * the brand's {@code <BRAND>_<PROFILE>} document in {@code ms_clp_blp_configuration}, edited directly in
 * MongoDB. A brand that declares nothing falls through to the value configured for the setting (an
 * environment variable). {@link #ALL} is the allow-list applied when that map is read, so a field
 * added to the document by hand can never become a setting by accident.</p>
 */
public final class LandingSettings {

    /**
     * Maximum number of entries in each product's {@code images} on a {@code products_list} carousel.
     * Zero or negative means no limit.
     */
    public static final String PRODUCTS_LIST_MAX_IMAGES = "products-list-max-images";

    /** Every id above. Iteration order is irrelevant; membership is the point. */
    public static final Set<String> ALL = Set.of(PRODUCTS_LIST_MAX_IMAGES);

    private LandingSettings() {
    }
}
