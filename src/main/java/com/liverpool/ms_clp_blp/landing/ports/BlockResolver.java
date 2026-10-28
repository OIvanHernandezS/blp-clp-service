package com.liverpool.ms_clp_blp.landing.ports;

import java.util.Set;

import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;

/**
 * Strategy for resolving one dynamic block type's detail during eager composition (Rule 19.5).
 *
 * <p>The core owns this contract; each dynamic block sub-slice provides exactly one implementation
 * (in its {@code adapters} package) that delegates to the block's own {@code *Service} — reusing the
 * block's circuit breaker and mapping. The composition service discovers implementations through the
 * {@link com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry} keyed by {@link #resolutionKeys()},
 * so the core never imports a block class (Open/Closed — a new block ships its own resolver).</p>
 *
 * <p>Implementations do their own blocking I/O; they run on virtual threads and may propagate the
 * block's failure signals ({@code ServiceUnavailableException} on an open breaker,
 * {@code DynamicBlockServiceUnavailableException} on a backend error). The composition service
 * catches those and degrades the block to a lazy placeholder — resolvers never swallow them.</p>
 */
public interface BlockResolver {

    /**
     * Declares which {@code (content type, source_of_data)} combinations this strategy resolves.
     *
     * <p>Usually a single key. A strategy claims more than one when the same backing service is reached
     * through different CMS shapes — the Salesforce products-list resolver, for example, serves both
     * {@code source_of_data: salesforce} and legacy entries that carry no {@code source_of_data} at
     * all.</p>
     *
     * <p>Every key returned here must also exist in the {@link
     * com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog}, or the block can never be classified
     * as dynamic and the resolver is dead code. Two strategies must not claim the same key;
     * {@code LandingConfig} fails startup if they do.</p>
     *
     * @return the non-empty set of routing keys this strategy owns
     */
    Set<BlockResolutionKey> resolutionKeys();

    /**
     * Resolves the block's detail for the current request.
     *
     * <p>Return {@code null} to <strong>drop</strong> the block entirely (e.g. it resolves to nothing
     * relevant for this session) — the composition service removes it from the page while preserving
     * the order of the surviving blocks. This is distinct from a <em>failure</em>: a thrown exception
     * (backend error / open circuit breaker) degrades the block to a lazy placeholder rather than
     * dropping it.</p>
     *
     * @param context block id, attributes, session and propagated gateway headers
     * @return the resolved, serialisable detail to inline in the {@code GET /blp|clp/**} response, or
     *         {@code null} to drop the block
     */
    ResolvedContent resolve(BlockResolutionContext context);
}
