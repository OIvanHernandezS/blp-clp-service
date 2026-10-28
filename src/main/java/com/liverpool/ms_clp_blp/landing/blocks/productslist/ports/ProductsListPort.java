package com.liverpool.ms_clp_blp.landing.blocks.productslist.ports;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Outbound port for one of the {@code products_list} block's backing data sources.
 *
 * <p>Unlike a single-provider block, this port has <strong>one implementation per
 * {@code source_of_data}</strong> (Salesforce, Jewel). {@code ProductsListUseCases} indexes them by
 * {@link #source()} and routes each query to the matching adapter, so provider selection lives in one
 * place instead of being spread across packages (ADR-029).</p>
 */
public interface ProductsListPort {

    /**
     * @return the backing service this adapter speaks to; must be unique across implementations, which
     *         the use case enforces at construction
     */
    SourceOfData source();

    /**
     * Why this query cannot produce anything, decided without any I/O — or {@code null} when it can.
     *
     * <p>Checked by the use case <strong>before</strong> the circuit breaker, so a business no-op — a
     * guest session Salesforce cannot personalise, a Jewel model whose required identity is missing, a
     * block with no model configured — neither consumes a breaker permit nor records a call. Those are
     * not failures and must not skew the breaker's failure-rate window (ADR-004, ADR-022).</p>
     *
     * <p>Returns the <em>reason</em> rather than a boolean because the answer travels all the way to the
     * frontend: the endpoint reports it as the response's {@code reason} beside an
     * {@link com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus#EMPTY} status, which is how the caller
     * knows the block is legitimately empty and must not be retried (ADR-043). The adapter already knows
     * which precondition failed — it logs it as {@code BLOCK_SKIPPED} — so collapsing that to a
     * {@code false} threw away the one thing the caller needed.</p>
     *
     * @param query the block to resolve
     * @return the reason this block resolves to nothing without calling the backing service, or
     *         {@code null} when the query can be fetched
     */
    BlockDropReason skipReason(ProductsListQuery query);

    /**
     * Whether the result of this query is identical for every caller.
     *
     * <p>Drives the {@code Cache-Control} header on the resolution endpoint: a carousel that does not
     * depend on the session can be cached at the edge, a personalised one never can.</p>
     *
     * @param query the block to resolve
     * @return {@code true} when the resolution carries no per-session personalisation
     */
    boolean sessionIndependent(ProductsListQuery query);

    /**
     * Fetches the carousel from the backing source. Only called when {@link #skipReason} returned
     * {@code null}, and always inside this provider's circuit breaker.
     *
     * @param query block id, session context and the provider's own settings
     * @return the resolved carousel, before central threshold handling
     */
    ProductsListResolution fetch(ProductsListQuery query);
}
