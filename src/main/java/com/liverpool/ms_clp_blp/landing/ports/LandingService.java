package com.liverpool.ms_clp_blp.landing.ports;

import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;

/**
 * Inbound port: compose a BLP or CLP landing page (ordering + static resolution + dynamic
 * placeholders + category data), and read a category with its children on its own. This is the only
 * entry point the REST adapters depend on (dependencies point inward, Rule 1).
 */
public interface LandingService {

    /**
     * Composes the landing page for the given query.
     *
     * @param query page type, locale, path and session context
     * @return the composed landing page
     */
    LandingPage getLandingPage(LandingPageQuery query);

    /**
     * Reads one category with its visible children, exactly as {@code categoryData} renders on a
     * landing page — including the indexer's navigation fields ({@code viewUrl}, {@code showpath},
     * {@code applyCLPRedirect}) on every node — but without the Contentstack redirect.
     *
     * @param brand      brand identifier, as sent in {@code x-brand-id}
     * @param categoryId the category id, as it appears in the storefront URL
     * @return the category with its children and breadcrumb
     */
    CategoryData getCategory(String brand, String categoryId);
}
