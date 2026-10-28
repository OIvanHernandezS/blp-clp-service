package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;
import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;

/**
 * Immutable lookup from a dynamic block's {@link BlockResolutionKey} to its eager
 * {@link BlockResolver} strategy.
 *
 * <p>Assembled once at startup from all discovered resolver beans (see {@code LandingConfig}), it lets
 * the composition service invoke the right strategy without knowing any block class — mirroring how
 * {@link BlockResolutionCatalog} supplies lazy endpoint metadata, and keyed identically so the eager
 * and lazy paths can never disagree about which service owns a block. A key with no registered
 * resolver simply stays lazy (Open/Closed — adding a resolver enables eager resolution for that key
 * with no core change).</p>
 *
 * @param resolvers per-key resolver strategies
 */
public record BlockResolverRegistry(Map<BlockResolutionKey, BlockResolver> resolvers) {

    public BlockResolverRegistry {
        resolvers = resolvers == null ? Map.of() : Map.copyOf(resolvers);
    }

    /**
     * @param key the dynamic block's content type + source
     * @return the resolver strategy if one is registered for this exact key
     */
    public Optional<BlockResolver> forKey(BlockResolutionKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(resolvers.get(key));
    }
}
