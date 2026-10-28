package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BlockPayloadNormalizerTest {

    private final BlockPayloadNormalizer normalizer = new BlockPayloadNormalizer();

    @Test
    void normalize_singleKeyWrapper_liftsContentTypeAndMetadataUid() {
        List<Map<String, Object>> raw = List.of(
                Map.of("hero_banner_slider", Map.of(
                        "title", "Hero",
                        "_metadata", Map.of("uid", "h1"))));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        assertThat(result).hasSize(1);
        assertThat(result.get(0))
                .containsEntry("_content_type_uid", "hero_banner_slider")
                .containsEntry("uid", "h1")
                .containsEntry("title", "Hero");
    }

    @Test
    void normalize_missingMetadataUid_leavesUidNull() {
        List<Map<String, Object>> raw = List.of(
                Map.of("container", Map.of("title", "C")));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        assertThat(result.get(0)).containsEntry("_content_type_uid", "container");
        assertThat(result.get(0)).containsKey("uid");
        assertThat(result.get(0).get("uid")).isNull();
    }

    @Test
    void normalize_alreadyFlatBlock_isIdempotent() {
        Map<String, Object> flat = Map.of(
                "_content_type_uid", "banner", "uid", "b1", "title", "T");

        List<Map<String, Object>> result = normalizer.normalize(List.of(flat));

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(flat);
    }

    @Test
    void normalize_doubleNestedBlock_unwrapsFirstElementMergedWithRest() {
        // Block value repeats its own key: { promo: { promo: [ {title:"inner"} ], footnote:"rest" } }
        List<Map<String, Object>> raw = List.of(
                Map.of("promo", Map.of(
                        "promo", List.of(Map.of("title", "inner")),
                        "footnote", "rest")));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        assertThat(result.get(0))
                .containsEntry("title", "inner")
                .containsEntry("footnote", "rest");
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalize_containerGrid_flattensItemGridEntries() {
        List<Map<String, Object>> raw = List.of(
                Map.of("container_grid", Map.of(
                        "container_grid", List.of(Map.of(
                                "item_grid", List.of(
                                        Map.of("promo_card", Map.of(
                                                "block", List.of(Map.of("title", "Promo")),
                                                "span", 2))))))));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        List<Map<String, Object>> itemGrid = (List<Map<String, Object>>) result.get(0).get("item_grid");
        assertThat(itemGrid).hasSize(1);
        assertThat(itemGrid.get(0))
                .containsEntry("item_grid_type", "promo_card")
                .containsEntry("title", "Promo")
                .containsEntry("span", 2)
                .doesNotContainKey("block");
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalize_tabsContainer_recursivelyNormalizesTabBlocks() {
        List<Map<String, Object>> raw = List.of(
                Map.of("tabs_container", Map.of(
                        "tabs_container", List.of(Map.of(
                                "tabs_manager", List.of(Map.of(
                                        "label", "Tab 1",
                                        "tab_blocks", List.of(
                                                Map.of("band", Map.of("_metadata", Map.of("uid", "bd1")))))))))));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        List<Map<String, Object>> tabs = (List<Map<String, Object>>) result.get(0).get("tabs_manager");
        List<Map<String, Object>> tabBlocks = (List<Map<String, Object>>) tabs.get(0).get("tab_blocks");
        assertThat(tabBlocks.get(0))
                .containsEntry("_content_type_uid", "band")
                .containsEntry("uid", "bd1");
    }

    @Test
    void normalize_preservesOrder() {
        List<Map<String, Object>> raw = List.of(
                Map.of("a", Map.of("_metadata", Map.of("uid", "1"))),
                Map.of("b", Map.of("_metadata", Map.of("uid", "2"))),
                Map.of("c", Map.of("_metadata", Map.of("uid", "3"))));

        List<Map<String, Object>> result = normalizer.normalize(raw);

        assertThat(result).extracting(b -> b.get("uid")).containsExactly("1", "2", "3");
    }
}
