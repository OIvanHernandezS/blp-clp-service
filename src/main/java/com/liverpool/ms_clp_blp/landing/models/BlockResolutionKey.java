package com.liverpool.ms_clp_blp.landing.models;

import java.util.Objects;

/**
 * Identifies which service resolves a dynamic block: its content type <em>and</em> its backing
 * source.
 *
 * <p>Used as the key of both {@link BlockResolutionCatalog} (lazy endpoint + flag metadata) and
 * {@link BlockResolverRegistry} (eager resolver strategies), so the two always agree on routing.</p>
 *
 * <p>Lookups are <strong>exact</strong> — there is intentionally no fallback from
 * {@code (type, source)} to {@code (type, NONE)}. A block whose source is unmapped must become a
 * static pass-through, not silently inherit the routing of an unrelated adapter that happens to share
 * its content type. Supporting a new source is one added catalog entry plus one added resolver key
 * (Open/Closed).</p>
 *
 * @param type   the block's Contentstack content type
 * @param source the block's backing system, from {@code source_of_data}
 */
public record BlockResolutionKey(BlockType type, SourceOfData source) {

    public BlockResolutionKey {
        Objects.requireNonNull(type, "type must not be null");
        source = source == null ? SourceOfData.NONE : source;
    }

    /**
     * @param type   the block's content type
     * @param source the block's backing system
     * @return the composite routing key
     */
    public static BlockResolutionKey of(BlockType type, SourceOfData source) {
        return new BlockResolutionKey(type, source);
    }

    /**
     * Key for a block type whose CMS entries carry no {@code source_of_data} — the common case for
     * types backed by exactly one service.
     *
     * @param type the block's content type
     * @return the composite routing key with {@link SourceOfData#NONE}
     */
    public static BlockResolutionKey of(BlockType type) {
        return new BlockResolutionKey(type, SourceOfData.NONE);
    }
}
