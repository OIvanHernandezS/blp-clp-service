package com.liverpool.ms_clp_blp.landing.models;

import java.util.Map;
import java.util.Optional;

import com.liverpool.ms_clp_blp.landing.ports.BlockParamsBuilder;

/**
 * Immutable lookup from a dynamic block's {@link BlockResolutionKey} to the
 * {@link BlockParamsBuilder} that projects its {@code ms_params}.
 *
 * <p>Assembled once at startup from all discovered builder beans (see {@code LandingConfig}), it lets the
 * composition service describe a block's request parameters without knowing any block class — mirroring
 * {@link BlockResolverRegistry}, and keyed identically to it and to {@link BlockResolutionCatalog} so the
 * advertised endpoint and the parameters advertised for it can never disagree about which service owns a
 * block.</p>
 *
 * <p>A key with no registered builder yields an <strong>empty</strong> object rather than a missing one.
 * That is the deliberate, correct answer for the three per-block endpoints that resolve from the path and
 * ignore the request body: an empty {@code ms_params} tells the frontend "this endpoint needs nothing
 * from you", whereas an absent one would be indistinguishable from a bug and would stringify to
 * {@code undefined} on the wire.</p>
 *
 * @param builders per-key parameter projections
 */
public record BlockParamsRegistry(Map<BlockResolutionKey, BlockParamsBuilder> builders) {

    public BlockParamsRegistry {
        builders = builders == null ? Map.of() : Map.copyOf(builders);
    }

    /**
     * @return a registry with no builders — every key resolves to an empty parameter object
     */
    public static BlockParamsRegistry empty() {
        return new BlockParamsRegistry(Map.of());
    }

    /**
     * @param key the dynamic block's content type + source
     * @return the builder if one is registered for this exact key
     */
    public Optional<BlockParamsBuilder> forKey(BlockResolutionKey key) {
        return key == null ? Optional.empty() : Optional.ofNullable(builders.get(key));
    }

    /**
     * Projects a block's parameters, or returns an empty object when no builder claims its key.
     *
     * @param key        the dynamic block's content type + source
     * @param blockId    the Contentstack uid of the block being described
     * @param attributes the block's raw Contentstack attributes
     * @return the parameters to advertise as {@code ms_params}; never {@code null}
     */
    public Map<String, Object> paramsFor(BlockResolutionKey key,
                                         String blockId,
                                         Map<String, Object> attributes) {
        return forKey(key)
                .map(builder -> builder.paramsFor(blockId, attributes))
                .orElseGet(Map::of);
    }
}
