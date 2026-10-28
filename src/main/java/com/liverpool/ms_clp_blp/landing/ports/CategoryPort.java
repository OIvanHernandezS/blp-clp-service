package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.models.CategoryData;

/**
 * Outbound port for the category tree a landing page belongs to (the category indexer).
 *
 * <p>Best effort by contract: an implementation answers {@link Optional#empty()} for an unknown
 * category or an unreachable indexer and never throws, because the page renders without its category
 * data rather than failing (digital_bff answered a BLP without {@code categoryData} the same way).</p>
 */
public interface CategoryPort {

    /**
     * @param brand      brand identifier, as sent in {@code x-brand-id}
     * @param categoryId the category id, as it appears in the storefront URL
     * @return the category with its visible children and breadcrumb, without any CMS redirect
     */
    Optional<CategoryData> findCategory(String brand, String categoryId);
}
