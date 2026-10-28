package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * The per-block settings that identify <em>which</em> backing service resolves a {@code products_list}
 * carousel and how.
 *
 * <p>{@code products_list} is served by more than one provider, told apart only by the CMS
 * {@code source_of_data} field (ADR-021). Each provider needs a different shape of settings — a
 * Salesforce carousel action versus a Jewel model config — and a different request identity, so both
 * travel together in one sealed type rather than as a widened union of nullable fields.</p>
 *
 * <p>Sealed so the compiler enforces that every provider is handled wherever these are consumed, and
 * framework-free so the hexagon core stays free of Spring (ArchitectureTest).</p>
 */
public sealed interface ProviderSettings permits SalesforceSettings, SalesforceV2Settings, JewelSettings, VertexSettings {

    /**
     * @return the backing service these settings address; the use case routes on this
     */
    SourceOfData source();

    /**
     * @return fewest products worth rendering — below this the carousel resolves to nothing and the
     *         caller drops the block; {@code null} when the block sets no floor
     */
    Integer minOfProducts();

    /**
     * @return most products to render, beyond which the list is truncated; {@code null} for no ceiling
     */
    Integer maxOfProducts();
}
