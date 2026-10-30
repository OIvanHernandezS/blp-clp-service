package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Produces the client-facing content map for a block from its raw Contentstack attributes.
 *
 * <p>For digital_bff parity (Rule 19) the block content must be a <strong>superset</strong> of the
 * reference BFF's block: digital_bff passes every CMS field through verbatim — including the entry
 * metadata ({@code _content_type_uid}, {@code uid}, {@code _metadata}, {@code _version}, {@code ACL},
 * {@code _in_progress}, {@code created_*}/{@code updated_*}, {@code publish_details}, {@code tags},
 * {@code title}, {@code locale}) and the routing flags ({@code audience_filter}, {@code enable_on_web},
 * {@code enable_on_apps}). None of these may be stripped, or the frontend contract breaks.</p>
 *
 * <p>So this step is a null-tolerant defensive copy: it retains every field and yields a mutable,
 * insertion-ordered map the mapper can enrich with the additive dynamic-resolution fields.</p>
 *
 */
@Component
public class BlockContentNormalizer {

    private static final String ATTR_SOURCE_OF_DATA = "source_of_data";

    /**
     * Returns a mutable, insertion-ordered copy of the raw block attributes with every CMS field
     * retained (digital_bff parity, Rule 19).
     *
     * @param attributes raw CMS attributes from {@code BlockDefinition.attributes()}
     * @return a mutable content map ready for JSON serialisation and additive enrichment
     */
    public Map<String, Object> normalize(Map<String, Object> attributes) {
        return attributes == null ? new LinkedHashMap<>() : new LinkedHashMap<>(attributes);
    }

    /**
     * Type-aware entry point, so every block shape goes through one call.
     *
     * <p>The one reshaping today: a {@code products_list} authored as the legacy
     * {@code source_of_data: groupby} is published as {@code vertex}, the provider that actually
     * resolves it (ADR-055) — exactly what digital_bff's Vertex strategy writes back onto the block.
     * Only the published content changes: {@code ms_params} is projected from the raw attributes, so
     * the lazy call still carries what the CMS authored.</p>
     *
     * @param blockType  the classified block type
     * @param attributes raw CMS attributes
     * @return a mutable content map
     */
    public Map<String, Object> normalize(BlockType blockType, Map<String, Object> attributes) {
        Map<String, Object> content = normalize(attributes);
        if (blockType == BlockType.PRODUCTS_LIST
                && SourceOfData.from(content.get(ATTR_SOURCE_OF_DATA)) == SourceOfData.GROUPBY) {
            content.put(ATTR_SOURCE_OF_DATA, SourceOfData.VERTEX.token());
        }
        return content;
    }
}
