package com.liverpool.ms_clp_blp.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.LoadStrategy;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.shared.web.CmsEventListNormalizer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backend-owned {@code index} counter.
 *
 * <p>This defect is invisible in a rendered page — a missing or misnumbered {@code value} only
 * corrupts analytics — so the tests pin the entire sequence rather than spot-checking, and cover the
 * traversal order that decides it.</p>
 */
class BffEventDecoratorTest {

    private final BffEventDecorator decorator = new BffEventDecorator();

    // ── numbering ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void decorate_numbersFromOne() {
        List<LandingBlock> result = decorator.decorate(List.of(block("b1", eventList("events", 1))));

        assertThat(indexValues(result)).containsExactly(1);
    }

    @Test
    void decorate_numbersRunContinuouslyAcrossBlocks() {
        List<LandingBlock> result = decorator.decorate(List.of(
                block("b1", eventList("events", 2)),
                block("b2", eventList("events", 3))));

        assertThat(indexValues(result)).containsExactly(1, 2, 3, 4, 5);
    }

    /** The live page nests carousels and cards several levels deep inside a container. */
    @Test
    void decorate_numbersNestedContentInDocumentOrder() {
        Map<String, Object> child = map("events", eventList("events", 1));
        Map<String, Object> content = map("events", eventList("events", 1));
        content.put("content_list", new ArrayList<>(List.of(child)));

        List<LandingBlock> result = decorator.decorate(List.of(new StaticBlock("b1", BlockType.CONTAINER, content)));

        // The block's own event list is numbered before its children are visited.
        assertThat(indexValues(result)).containsExactly(1, 2);
    }

    /**
     * Every {@code button_events} index on the page precedes every {@code events} index, regardless
     * of nesting — the legacy implementation makes one full pass per event-list name.
     */
    @Test
    void decorate_completesEachEventListNamePassBeforeTheNext() {
        Map<String, Object> content = map("events", eventList("events", 1));
        content.put("button_events", eventList("button_events", 1));

        List<LandingBlock> result = decorator.decorate(List.of(new StaticBlock("b1", BlockType.CONTAINER, content)));

        StaticBlock block = (StaticBlock) result.get(0);
        assertThat(indexValueOf(block.content(), "button_events")).isEqualTo(1);
        assertThat(indexValueOf(block.content(), "events")).isEqualTo(2);
    }

    @Test
    void decorate_numbersDynamicPlaceholderContentToo() {
        DynamicPlaceholder placeholder = new DynamicPlaceholder(
                "b1", BlockType.PRODUCTS_LIST, map("events", eventList("events", 1)),
                "/p", null, "flag", DynamicBlockStatus.AVAILABLE, "POST", LoadStrategy.LAZY, true,
                Map.of("uid", "b1", "source_of_data", "jewel"));

        List<LandingBlock> result = decorator.decorate(List.of(placeholder));

        assertThat(indexValues(result)).containsExactly(1);
        // Placeholder metadata survives the rebuild.
        DynamicPlaceholder decorated = (DynamicPlaceholder) result.get(0);
        assertThat(decorated.resolutionPath()).isEqualTo("/p");
        assertThat(decorated.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(decorated.loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        assertThat(decorated.msParams()).containsExactlyInAnyOrderEntriesOf(
                Map.of("uid", "b1", "source_of_data", "jewel"));
    }

    /**
     * This decorator rebuilds every block on every page, so a field it forgets to carry is silently
     * empty in production while composition's own tests still pass. {@code ms_params} is exactly that
     * kind of field, and this pins it for the eager shape too — the one with no placeholder test above.
     */
    @Test
    void decorate_carriesMsParamsThroughResolvedBlocks() {
        ResolvedBlock resolved = new ResolvedBlock(
                "b2", BlockType.PRODUCTS_LIST, map("events", eventList("events", 1)),
                Map.of("products", List.of("a")), "/blp/blocks/products-list", "POST",
                LoadStrategy.EAGER, DynamicBlockStatus.AVAILABLE, false,
                Map.of("uid", "b2", "source_of_data", "salesforce",
                        "salesforce_carousel", "CMSOfertasIncreibles"));

        ResolvedBlock decorated = (ResolvedBlock) decorator.decorate(List.of(resolved)).get(0);

        assertThat(decorated.msParams()).containsExactlyInAnyOrderEntriesOf(
                Map.of("uid", "b2", "source_of_data", "salesforce",
                        "salesforce_carousel", "CMSOfertasIncreibles"));
        assertThat(decorated.payload()).isEqualTo(Map.of("products", List.of("a")));
    }

    // ── what must not be touched ──────────────────────────────────────────────────────────────────

    @Test
    void decorate_leavesSelectedStoreEntriesAlone() {
        Map<String, Object> entry = map("source", "bff");
        entry.put("type", "selected_store.name");
        List<LandingBlock> result = decorator.decorate(List.of(block("b1", eventListOf(List.of(entry)))));

        assertThat(customData(result).get(0)).doesNotContainKey("value");
    }

    @Test
    void decorate_leavesManuallyAuthoredEntriesAlone() {
        Map<String, Object> entry = map("source", "manual");
        entry.put("type", "index");
        entry.put("value", "ecommerce");
        List<LandingBlock> result = decorator.decorate(List.of(block("b1", eventListOf(List.of(entry)))));

        assertThat(customData(result).get(0)).containsEntry("value", "ecommerce");
    }

    @Test
    void decorate_doesNotMutateTheInput() {
        Map<String, Object> content = map("events", eventList("events", 1));
        StaticBlock original = new StaticBlock("b1", BlockType.CONTAINER, content);

        decorator.decorate(List.of(original));

        assertThat(indexValues(List.of(original))).containsOnlyNulls();
    }

    @Test
    void decorate_preservesBlockOrderAndCount() {
        List<LandingBlock> result = decorator.decorate(List.of(
                block("b1", eventList("events", 1)),
                block("b2", eventList("events", 1)),
                block("b3", eventList("events", 1))));

        assertThat(result).extracting(LandingBlock::blockId).containsExactly("b1", "b2", "b3");
    }

    // ── edge cases (CMS shapes are not guaranteed) ────────────────────────────────────────────────

    @Test
    void decorate_emptyList_returnsEmpty() {
        assertThat(decorator.decorate(List.of())).isEmpty();
    }

    @Test
    void decorate_nullList_returnsEmpty() {
        assertThat(decorator.decorate(null)).isEmpty();
    }

    @Test
    void decorate_blocksWithNoEvents_areReturnedUnchanged() {
        List<LandingBlock> input = List.of(new StaticBlock("b1", BlockType.CONTAINER, map("title", "hello")));

        assertThat(decorator.decorate(input)).isSameAs(input);
    }

    @Test
    void decorate_eventListOfWrongType_isIgnored() {
        List<LandingBlock> input = List.of(new StaticBlock("b1", BlockType.CONTAINER, map("events", "not-a-list")));

        assertThat(decorator.decorate(input)).isSameAs(input);
    }

    /**
     * The CMS publishes some {@code events} as an empty object; {@link CmsEventListNormalizer} turns
     * those into an empty list before composition. An empty list contributes no entries, so it must
     * not consume a number and shift every index that follows.
     */
    @Test
    void decorate_emptyEventList_doesNotConsumeAnIndex() {
        List<LandingBlock> input = List.of(
                block("b1", new ArrayList<>()),
                block("b2", eventList("promo", 2)));

        List<LandingBlock> decorated = decorator.decorate(input);

        assertThat(indexValues(List.of(decorated.get(0)))).isEmpty();
        assertThat(indexValues(List.of(decorated.get(1)))).containsExactly(1, 2);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static StaticBlock block(String id, List<Object> events) {
        return new StaticBlock(id, BlockType.CONTAINER, map("events", events));
    }

    private static Map<String, Object> map(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    /** One event carrying {@code count} backend-owned index entries. */
    private static List<Object> eventList(String name, int count) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> entry = map("name", name + "-" + i);
            entry.put("type", "index");
            entry.put("source", "bff");
            entries.add(entry);
        }
        return eventListOf(entries);
    }

    private static List<Object> eventListOf(List<Map<String, Object>> customData) {
        Map<String, Object> event = map("customData", new ArrayList<>(customData));
        return new ArrayList<>(List.of(event));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> customData(List<LandingBlock> blocks) {
        List<Map<String, Object>> found = new ArrayList<>();
        for (LandingBlock block : blocks) {
            Map<String, Object> content = ((StaticBlock) block).content();
            for (Object event : (List<Object>) content.get("events")) {
                found.addAll((List<Map<String, Object>>) ((Map<String, Object>) event).get("customData"));
            }
        }
        return found;
    }

    /** Every {@code index} value in the tree, in document order; null when unset. */
    private static List<Object> indexValues(List<LandingBlock> blocks) {
        List<Object> values = new ArrayList<>();
        for (LandingBlock block : blocks) {
            collectIndexValues(contentOf(block), values);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static void collectIndexValues(Object node, List<Object> out) {
        if (node instanceof List<?> list) {
            list.forEach(element -> collectIndexValues(element, out));
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        if ("bff".equals(map.get("source")) && "index".equals(map.get("type"))) {
            out.add(map.get("value"));
        }
        ((Map<String, Object>) map).values().forEach(value -> collectIndexValues(value, out));
    }

    @SuppressWarnings("unchecked")
    private static Object indexValueOf(Map<String, Object> content, String eventListName) {
        Map<String, Object> event = (Map<String, Object>) ((List<Object>) content.get(eventListName)).get(0);
        return ((List<Map<String, Object>>) event.get("customData")).get(0).get("value");
    }

    private static Map<String, Object> contentOf(LandingBlock block) {
        return block instanceof StaticBlock s ? s.content() : ((DynamicPlaceholder) block).content();
    }
}
