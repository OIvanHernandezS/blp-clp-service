package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalizer is a metadata-retaining defensive copy: for digital_bff parity (Rule 19) every CMS
 * field — including entry metadata and routing flags — must survive into the block content.
 */
class BlockContentNormalizerTest {

    private BlockContentNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new BlockContentNormalizer();
    }

    @Test
    void normalize_retainsAllCmsMetadataAndRoutingFlags() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("_content_type_uid", "banner");
        attributes.put("uid", "blt123");
        attributes.put("title", "My Block");
        attributes.put("_version", 3);
        attributes.put("ACL", Map.of());
        attributes.put("_in_progress", false);
        attributes.put("created_by", "user-1");
        attributes.put("updated_by", "user-2");
        attributes.put("created_at", "2024-01-01T00:00:00.000Z");
        attributes.put("updated_at", "2024-01-01T00:00:00.000Z");
        attributes.put("publish_details", Map.of("locale", "es-mx"));
        attributes.put("_metadata", Map.of("uid", "blt123"));
        attributes.put("tags", List.of());
        attributes.put("audience_filter", "all");
        attributes.put("enable_on_web", true);
        attributes.put("enable_on_apps", true);

        Map<String, Object> result = normalizer.normalize(attributes);

        assertThat(result).containsAllEntriesOf(attributes);
    }

    @Test
    void normalize_passesRealContentFieldsThroughUnchanged() {
        // CMS fields must be preserved verbatim (no renaming / reshaping) — parity with digital_bff.
        Map<String, Object> item = Map.of("label", "Zapatos", "url", "/zapatos");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", "Categorías");
        attributes.put("content_list", List.of(item));  // NOT renamed to "items"
        attributes.put("spacing", "compact");
        attributes.put("_metadata", Map.of("uid", "cs123"));

        Map<String, Object> result = normalizer.normalize(attributes);

        assertThat(result.get("title")).isEqualTo("Categorías");
        assertThat(result.get("content_list")).isEqualTo(List.of(item));
        assertThat(result.get("spacing")).isEqualTo("compact");
        assertThat(result.get("_metadata")).isEqualTo(Map.of("uid", "cs123"));
    }

    @Test
    void normalize_returnsMutableCopy_notBackingMap() {
        Map<String, Object> attributes = Map.of("title", "x");

        Map<String, Object> result = normalizer.normalize(attributes);
        result.put("loadStrategy", "LAZY"); // mapper enriches the returned map — must not throw

        assertThat(result).containsEntry("loadStrategy", "LAZY");
        assertThat(attributes).doesNotContainKey("loadStrategy");
    }

    @Test
    void normalize_preservesNullValuedFields() {
        // Real CMS blocks carry null-valued optional fields; they must survive (not NPE, not dropped).
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("title", null);
        attributes.put("subtitle", null);

        Map<String, Object> result = normalizer.normalize(attributes);

        assertThat(result).containsKey("title");
        assertThat(result).containsKey("subtitle");
        assertThat(result.get("title")).isNull();
    }

    @Test
    void normalize_preservesInsertionOrder() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("a", 1);
        attributes.put("_version", 9);
        attributes.put("b", 2);
        attributes.put("c", 3);

        Map<String, Object> result = normalizer.normalize(attributes);

        assertThat(result.keySet()).containsExactly("a", "_version", "b", "c");
    }

    @Test
    void normalize_nullInput_returnsEmptyMutableMap() {
        Map<String, Object> result = normalizer.normalize(null);
        result.put("k", "v");

        assertThat(result).containsExactly(Map.entry("k", "v"));
    }

    // ── the one CMS field reshape this service performs (container_shortcuts, legacy parity) ──────

    /**
     * A legacy {@code groupby} carousel is resolved by Vertex (ADR-055) and published as such, like
     * digital_bff's Vertex strategy does; any other source, and any other block type, is untouched.
     */
    @Test
    void normalize_groupbyProductsList_isPublishedAsVertex() {
        Map<String, Object> groupby = Map.of("uid", "blt1", "source_of_data", "groupby");
        Map<String, Object> jewel = Map.of("uid", "blt2", "source_of_data", "jewel");

        assertThat(normalizer.normalize(BlockType.PRODUCTS_LIST, groupby)).containsEntry("source_of_data", "vertex");
        assertThat(normalizer.normalize(BlockType.PRODUCTS_LIST, jewel)).containsEntry("source_of_data", "jewel");
        assertThat(normalizer.normalize(BlockType.BUTTON_LIST, groupby)).containsEntry("source_of_data", "groupby");
    }

    /** The type-aware entry point is the same defensive copy for every other landing block type. */
    @Test
    void normalize_withBlockType_isTheSameDefensiveCopy() {
        Map<String, Object> attributes = Map.of("title", "Chips", "items", List.of(Map.of("label", "Labiales")));

        assertThat(normalizer.normalize(BlockType.BUTTON_LIST, attributes)).isEqualTo(attributes);
        assertThat(normalizer.normalize(null, attributes)).isEqualTo(attributes);
        assertThat(normalizer.normalize(BlockType.PRODUCTS_LIST, null)).isEmpty();
    }
}
