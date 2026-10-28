package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;
import java.util.Optional;

/**
 * Immutable lookup from a dynamic block's {@link BlockResolutionKey} to its {@link BlockResolution}
 * (endpoint + flag).
 *
 * <p>Lets the composition service build placeholders without knowing how endpoints/flags are
 * configured. Keyed by content type <em>and</em> {@code source_of_data}, because the live template
 * ships {@code products_list} blocks backed by different services; see {@link BlockResolutionKey} for
 * why lookups are exact. New dynamic blocks (or new sources for an existing block) are added by
 * extending the backing map (Open/Closed).</p>
 *
 * @param resolutions per-key resolution metadata
 */
public record BlockResolutionCatalog(Map<BlockResolutionKey, BlockResolution> resolutions) {

    public BlockResolutionCatalog {
        resolutions = resolutions == null ? Map.of() : Map.copyOf(resolutions);
    }

    /**
     * @param key the dynamic block's content type + source
     * @return the resolution metadata if configured for this exact key
     */
    public Optional<BlockResolution> forKey(BlockResolutionKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(resolutions.get(key));
    }
}
