package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.List;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;

/**
 * A resolved {@code products_list} carousel, from either provider.
 *
 * <p>{@code title}/{@code campaignName} are Salesforce campaign metadata; the raw Jewel response carries
 * neither, so the Jewel path leaves them {@code null} and the REST mapper omits them (ADR-029).</p>
 *
 * <p>{@code status} lets a per-block outcome travel through a batch response instead of throwing, so one
 * provider outage degrades one carousel rather than the whole call. {@code reason} names the rule behind
 * a non-{@link DynamicBlockStatus#AVAILABLE} status, and travels to the frontend so an
 * {@link DynamicBlockStatus#EMPTY} carousel is dropped rather than retried (ADR-043).</p>
 *
 * @param blockId      the resolved block id
 * @param title        carousel display title; {@code null} for Jewel
 * @param campaignName Salesforce campaign name; {@code null} for Jewel
 * @param products     products to render; never {@code null}
 * @param status       {@link DynamicBlockStatus#AVAILABLE} when there are products to draw,
 *                     {@link DynamicBlockStatus#EMPTY} when resolution succeeded but produced nothing,
 *                     {@link DynamicBlockStatus#UNAVAILABLE} when the backing service failed
 * @param reason       why the status is not {@code AVAILABLE}; {@code null} exactly when it is
 */
public record ProductsListResolution(
        String blockId,
        String title,
        String campaignName,
        List<ProductRecommendation> products,
        DynamicBlockStatus status,
        BlockDropReason reason) {

    public ProductsListResolution {
        products = products == null ? List.of() : List.copyOf(products);
        status = status == null ? DynamicBlockStatus.AVAILABLE : status;
        // An AVAILABLE block has nothing to explain, and carrying a stale reason through withProducts()
        // would let one surface on the happy path.
        reason = status == DynamicBlockStatus.AVAILABLE ? null : reason;
    }

    /**
     * A carousel that resolved successfully and has products to draw.
     *
     * @param blockId      the resolved block id
     * @param title        carousel display title; {@code null} for providers that carry none
     * @param campaignName campaign name; {@code null} for providers that carry none
     * @param products     the products to render
     * @return an {@code AVAILABLE} resolution
     */
    public static ProductsListResolution available(String blockId,
                                                   String title,
                                                   String campaignName,
                                                   List<ProductRecommendation> products) {
        return new ProductsListResolution(
                blockId, title, campaignName, products, DynamicBlockStatus.AVAILABLE, null);
    }

    /**
     * A successful resolution carrying no products — a guest session Salesforce cannot personalise, a
     * Jewel model whose identity requirements are unmet, or a carousel below its
     * {@code min_of_products}. The frontend drops the block rather than rendering an empty shell, and
     * must not retry: {@code reason} is what tells it so.
     *
     * @param blockId the block that resolved to nothing
     * @param reason  which rule left it empty
     * @return an empty {@code EMPTY} resolution
     */
    public static ProductsListResolution empty(String blockId, BlockDropReason reason) {
        return new ProductsListResolution(blockId, null, null, List.of(), DynamicBlockStatus.EMPTY, reason);
    }

    /**
     * A failed resolution — the backing service errored or its circuit breaker is open. Worth retrying,
     * unlike {@link #empty}.
     *
     * @param blockId the block that could not be resolved
     * @param reason  {@link BlockDropReason#UPSTREAM_FAILED} or
     *                {@link BlockDropReason#CIRCUIT_BREAKER_OPEN}
     * @return an empty {@code UNAVAILABLE} resolution
     */
    public static ProductsListResolution unavailable(String blockId, BlockDropReason reason) {
        return new ProductsListResolution(
                blockId, null, null, List.of(), DynamicBlockStatus.UNAVAILABLE, reason);
    }

    /**
     * @param products the products this carousel resolved to
     * @return a copy carrying {@code products}, preserving this resolution's campaign metadata
     */
    public ProductsListResolution withProducts(List<ProductRecommendation> products) {
        return new ProductsListResolution(blockId, title, campaignName, products, status, reason);
    }

    /**
     * Discards the products this carousel resolved to and records why, keeping its campaign metadata for
     * the log line. Used where a provider answered with products but a block-level rule rejects them —
     * {@code min_of_products} today.
     *
     * @param reason which rule rejected them
     * @return an {@code EMPTY} copy carrying no products
     */
    public ProductsListResolution emptied(BlockDropReason reason) {
        return new ProductsListResolution(
                blockId, title, campaignName, List.of(), DynamicBlockStatus.EMPTY, reason);
    }

    /**
     * @return {@code true} when there is nothing worth rendering, so the caller drops the block
     */
    public boolean isEmpty() {
        return products.isEmpty();
    }
}
