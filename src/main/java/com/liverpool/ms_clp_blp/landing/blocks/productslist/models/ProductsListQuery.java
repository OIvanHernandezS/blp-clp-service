package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Request to resolve one dynamic {@code products_list} block.
 *
 * <p>Provider-agnostic: everything specific to the backing service lives in {@link #provider()}, so the
 * use case can route, apply thresholds and drive the right circuit breaker without knowing whether the
 * carousel comes from Salesforce or Jewel (ADR-029). The A/B rule sits beside it rather than inside it
 * for the same reason: every provider honours it identically.</p>
 *
 * @param blockId             the placeholder block id being resolved
 * @param session             session context (brand drives Jewel integration-id resolution; its cohort
 *                            markers answer the A/B rule)
 * @param provider            the block's own CMS settings and the request identity its backing service
 *                            needs
 * @param conditionalShowHide the block's A/B {@code conditional_show_hide} rule; {@code null} is read as
 *                            {@link ConditionalShowHide#NONE}
 */
public record ProductsListQuery(
        String blockId,
        SessionContext session,
        ProviderSettings provider,
        ConditionalShowHide conditionalShowHide) {

    public ProductsListQuery {
        conditionalShowHide = conditionalShowHide == null ? ConditionalShowHide.NONE : conditionalShowHide;
    }

    /**
     * @return the backing service that owns this block, from its {@code source_of_data}
     */
    public SourceOfData source() {
        return provider.source();
    }
}
