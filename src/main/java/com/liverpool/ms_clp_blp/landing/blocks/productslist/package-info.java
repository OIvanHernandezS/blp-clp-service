/**
 * Products-list block sub-slice — answers {@code POST /blp|clp/blocks/products-list}.
 *
 * <p>Resolves the {@code products_list} carousel against <strong>every</strong> service that backs it.
 * This is the one block with more than one provider: Salesforce Evergage campaigns and JewelML models
 * both arrive as this content type and are told apart only by the CMS {@code source_of_data} field
 * (ADR-021). Each provider is an adapter under {@code adapters/<provider>/} implementing the single
 * {@link com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort}, indexed by
 * {@link com.liverpool.ms_clp_blp.landing.models.SourceOfData} — so adding a provider means adding one
 * adapter and one catalog entry, never a new slice (ADR-029).</p>
 *
 * <p>Naming: provider-agnostic classes are {@code ProductsList*}; provider implementations that are
 * Spring beans are {@code ProductsList<Provider>*} so their default bean names stay unique across
 * slices; framework-free provider records and {@code new}-ed helpers are {@code <Provider>*}.</p>
 *
 * <p>The endpoint is batch-first: it takes an array of blocks and resolves them concurrently on virtual
 * threads, answering with per-block status so one provider outage degrades one carousel rather than the
 * whole call. Inbound port:
 * {@link com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService}, implemented by
 * {@link com.liverpool.ms_clp_blp.landing.blocks.productslist.ProductsListUseCases}, which owns provider
 * routing, the per-provider circuit breakers and the {@code min_of_products}/{@code max_of_products}
 * thresholds.</p>
 *
 * <p>May depend on landing-owned contracts ({@code landing.ports}, {@code landing.models}, {@code landing.errors}),
 * {@code shared} and {@code clients} — never on landing adapters or sibling blocks (enforced by
 * ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.landing.blocks.productslist;
