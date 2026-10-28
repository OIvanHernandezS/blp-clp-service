package com.liverpool.ms_clp_blp.landing.models;

import java.util.Set;

/**
 * The complete set of feature-flag ids ms-clp-blp recognises — the single place that answers "which
 * flags exist".
 *
 * <p>Every flag here governs a {@code products_list} carousel: one per provider, so any provider can
 * be switched off without touching the others (ADR-029), plus the two carousel-wide behaviours —
 * A/B cohorts and the product blacklist. Landing pages carry no other dynamic block, so there is
 * nothing else to switch.</p>
 *
 * <p>{@link #ALL} is also the allow-list applied to CMS-authored configuration (per-brand flags):
 * an entry may carry any fields an editor likes, and only these ids are ever read from it. That keeps
 * an unrecognised CMS field from becoming an accidental flag.</p>
 */
public final class LandingFeatureFlags {

    /** {@code products_list} carousels backed by Salesforce, plus legacy entries with no source. */
    public static final String PRODUCTS_LIST_SALESFORCE = "products-list-salesforce";

    /** {@code products_list} carousels backed by Salesforce Personalization (V2). */
    public static final String PRODUCTS_LIST_SALESFORCE_V2 = "products-list-salesforce-v2";

    /** {@code products_list} carousels backed by the Jewel recommendation engine. */
    public static final String JEWEL = "jewel";

    /** {@code products_list} carousels backed by Vertex AI category search (and legacy {@code groupby}). */
    public static final String VERTEX = "vertex";

    /**
     * Honours the A/B {@code conditional_show_hide} rule on {@code products_list} carousels. Off means
     * every rule is ignored and the block resolves as if it had none — the same switch as
     * {@code isConditionalShowHideEnabled} in {@code platform_commons}.
     */
    public static final String CONDITIONAL_SHOW_HIDE = "conditional-show-hide";

    /**
     * Removes blacklisted products from every carousel — the same switch as ms_pdp_search's
     * {@code isCarouselRestrictedProductEnabled}. Off serves carousels exactly as the provider answered.
     */
    public static final String CAROUSEL_RESTRICTED_PRODUCTS = "carousel-restricted-products";

    /** Every id above. Iteration order is irrelevant; membership is the point. */
    public static final Set<String> ALL = Set.of(
            PRODUCTS_LIST_SALESFORCE,
            PRODUCTS_LIST_SALESFORCE_V2,
            JEWEL,
            VERTEX,
            CONDITIONAL_SHOW_HIDE,
            CAROUSEL_RESTRICTED_PRODUCTS);

    /**
     * The flag that gates one {@code products_list} provider — shared by the composition catalog and
     * the products-list endpoint's own gate, so turning a provider off silences both the inlined
     * carousel and a direct {@code POST …/blocks/products-list}.
     *
     * @param source the block's {@code source_of_data}
     * @return the flag id, or {@code null} for a source no provider serves
     */
    public static String productsListFlagId(SourceOfData source) {
        return switch (source) {
            case JEWEL -> JEWEL;
            case VERTEX, GROUPBY -> VERTEX;
            case SALESFORCE, NONE -> PRODUCTS_LIST_SALESFORCE;
            case SALESFORCE_V2 -> PRODUCTS_LIST_SALESFORCE_V2;
            case UNSUPPORTED -> null;
        };
    }

    private LandingFeatureFlags() {
    }
}
