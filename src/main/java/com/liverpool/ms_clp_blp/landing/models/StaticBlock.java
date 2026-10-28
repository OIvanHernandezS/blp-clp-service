package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A block with no session/runtime dependency, fully resolved from Contentstack at composition time
 * and eligible for long-lived caching (Rule 18).
 *
 * @param blockId  Contentstack block uid
 * @param blockType classified type (e.g. {@link BlockType#BANNER})
 * @param content  resolved, immutable content payload as delivered by Contentstack
 */
public record StaticBlock(String blockId, BlockType blockType, Map<String, Object> content)
        implements LandingBlock {

    public StaticBlock {
        // Null-tolerant unmodifiable copy: pass-through CMS content carries null-valued fields, which
        // Map.copyOf rejects with an NPE. Insertion order is preserved for response fidelity.
        content = content == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(content));
    }
}
