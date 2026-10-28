package com.liverpool.ms_clp_blp.landing.blocks.productslist.ports;

import java.util.List;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;

/**
 * Inbound port for the {@code products_list} resolution endpoint, covering every provider that backs the
 * block (Salesforce, Jewel) behind one contract (ADR-029).
 */
public interface ProductsListService {

    /**
     * Resolves one dynamic product carousel.
     *
     * @param query block id, session context and the block's provider settings
     * @return the resolved carousel detail
     */
    ProductsListResolution resolve(ProductsListQuery query);

    /**
     * Resolves several carousels concurrently, one virtual thread each, degrading per block so a single
     * provider failure cannot fail the rest.
     *
     * @param queries the blocks to resolve, in the caller's order
     * @return one resolution per query, in the same order; failed entries carry
     *         {@link com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus#UNAVAILABLE}
     */
    List<ProductsListResolution> resolveAll(List<ProductsListQuery> queries);

    /**
     * Whether every one of these blocks resolves identically for all callers, so the response may be
     * cached at the edge. One personalised block makes the whole response uncacheable.
     *
     * @param queries the blocks in the request
     * @return {@code true} only when all blocks are session-independent
     */
    boolean allSessionIndependent(List<ProductsListQuery> queries);
}
