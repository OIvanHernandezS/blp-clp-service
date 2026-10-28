package com.liverpool.ms_clp_blp.shared.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Pins the one shape change ms-clp-blp makes to CMS event lists: an empty object becomes an empty array,
 * and nothing else is touched.
 *
 * <p>The "leave it alone" cases matter as much as the conversion — a populated object turned into
 * {@code []} would drop CMS data with no trace, which is worse than the bug being fixed.</p>
 */
class CmsEventListNormalizerTest {

    // ── the conversion ────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"events", "button_events", "dot_events"})
    void emptyObject_becomesEmptyArray(String key) {
        Map<String, Object> node = mutableMap(key, new LinkedHashMap<>());

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get(key)).isEqualTo(List.of());
    }

    @Test
    void everyEventListOnTheSameObject_isNormalized() {
        Map<String, Object> node = new LinkedHashMap<>();
        CmsEventListNormalizer.EVENT_LIST_KEYS.forEach(key -> node.put(key, new LinkedHashMap<>()));

        CmsEventListNormalizer.normalize(node);

        assertThat(node).allSatisfy((key, value) -> assertThat(value).isEqualTo(List.of()));
    }

    // ── everything else is left exactly as authored ───────────────────────────────────────────────

    @Test
    void populatedList_isKeptAsTheSameInstance() {
        List<Object> authored = new ArrayList<>(List.of(Map.of("name", "click")));
        Map<String, Object> node = mutableMap("events", authored);

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get("events")).isSameAs(authored);
    }

    /** A populated object is not the reported bug; rewriting it would silently discard CMS data. */
    @Test
    void populatedObject_isLeftUntouched() {
        Map<String, Object> authored = mutableMap("name", "click");
        Map<String, Object> node = mutableMap("events", authored);

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get("events")).isSameAs(authored);
    }

    @Test
    void nullValue_isLeftUntouched() {
        Map<String, Object> node = mutableMap("events", null);

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get("events")).isNull();
        assertThat(node).containsKey("events");
    }

    @Test
    void scalarValue_isLeftUntouched() {
        Map<String, Object> node = mutableMap("events", "not-a-list");
        node.put("dot_events", 3);

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get("events")).isEqualTo("not-a-list");
        assertThat(node.get("dot_events")).isEqualTo(3);
    }

    /** Only the exact field names are in scope — a CMS field that merely ends in "events" is not. */
    @Test
    void keyOutsideTheFamily_isLeftUntouched() {
        Map<String, Object> node = mutableMap("custom_events", new LinkedHashMap<>());
        node.put("eventsList", new LinkedHashMap<>());

        CmsEventListNormalizer.normalize(node);

        assertThat(node.get("custom_events")).isEqualTo(Map.of());
        assertThat(node.get("eventsList")).isEqualTo(Map.of());
    }

    // ── the shapes the production fixture actually contains ───────────────────────────────────────

    /** Mirrors {@code container.container[0].button_with_event.events} — five of the seven real cases. */
    @Test
    void nestedInsideAnotherObject_isNormalized() {
        Map<String, Object> buttonWithEvent = mutableMap("label", "Ver más");
        buttonWithEvent.put("events", new LinkedHashMap<>());
        Map<String, Object> node = mutableMap("button_with_event", buttonWithEvent);

        CmsEventListNormalizer.normalize(node);

        assertThat(buttonWithEvent.get("events")).isEqualTo(List.of());
        assertThat(buttonWithEvent.get("label")).isEqualTo("Ver más");
    }

    /** Mirrors {@code band.band[0].content_list[7].band.events} — the empty object sits under lists. */
    @Test
    void nestedInsideListsAndObjects_isNormalized() {
        Map<String, Object> deep = mutableMap("events", new LinkedHashMap<>());
        Map<String, Object> node = mutableMap(
                "band", List.of(mutableMap("content_list", List.of(mutableMap("band", deep)))));

        CmsEventListNormalizer.normalize(node);

        assertThat(deep.get("events")).isEqualTo(List.of());
    }

    @Test
    void mixedShapesAcrossTheTree_areNormalizedIndependently() {
        List<Object> authored = new ArrayList<>(List.of(Map.of("name", "impression")));
        Map<String, Object> broken = mutableMap("events", new LinkedHashMap<>());
        Map<String, Object> intact = mutableMap("events", authored);
        Map<String, Object> node = mutableMap("blocks", List.of(broken, intact));

        CmsEventListNormalizer.normalize(node);

        assertThat(broken.get("events")).isEqualTo(List.of());
        assertThat(intact.get("events")).isSameAs(authored);
    }

    /** The normalized value must be mutable: downstream code rebuilds and enriches these trees. */
    @Test
    @SuppressWarnings("unchecked")
    void normalizedValue_isMutable() {
        Map<String, Object> node = mutableMap("events", new LinkedHashMap<>());

        CmsEventListNormalizer.normalize(node);

        assertThatCode(() -> ((List<Object>) node.get("events")).add("x")).doesNotThrowAnyException();
    }

    // ── degenerate input ──────────────────────────────────────────────────────────────────────────

    @Test
    void nullEmptyAndScalarRoots_areNoOps() {
        assertThatCode(() -> {
            CmsEventListNormalizer.normalize(null);
            CmsEventListNormalizer.normalize(new LinkedHashMap<>());
            CmsEventListNormalizer.normalize(new ArrayList<>());
            CmsEventListNormalizer.normalize("plain");
        }).doesNotThrowAnyException();
    }

    @Test
    void listRoot_isTraversed() {
        Map<String, Object> entry = mutableMap("events", new LinkedHashMap<>());

        CmsEventListNormalizer.normalize(new ArrayList<>(List.of(entry)));

        assertThat(entry.get("events")).isEqualTo(List.of());
    }

    /** CMS entries carry null-valued optional fields; walking them must not NPE. */
    @Test
    void nullValuesElsewhereInTheTree_areTolerated() {
        Map<String, Object> node = mutableMap("title", null);
        node.put("children", Arrays.asList(null, mutableMap("events", new LinkedHashMap<>())));

        assertThatCode(() -> CmsEventListNormalizer.normalize(node)).doesNotThrowAnyException();
    }

    private static Map<String, Object> mutableMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }
}
