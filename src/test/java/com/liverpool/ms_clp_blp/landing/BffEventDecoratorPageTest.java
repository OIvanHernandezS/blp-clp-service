package com.liverpool.ms_clp_blp.landing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of {@code processBffCustomDataEvents} a landing page adds over the Home: the favourite
 * store, and the entry's own page-level {@code events} numbered after every block.
 */
class BffEventDecoratorPageTest {

    private final BffEventDecorator decorator = new BffEventDecorator();

    @Test
    void storeEntries_takeTheStoresNameAndCode_inBlocksAndPageEvents() {
        List<LandingBlock> blocks = List.of(block("b1", events(bff("selected_store.name"), bff("selected_store.code"))));
        Map<String, Object> entry = new LinkedHashMap<>(Map.of("events", events(bff("selected_store.name"))));

        BffEventDecorator.Decorated decorated =
                decorator.decorate(blocks, entry, new SelectedStore(7, "Liverpool Santa Fe"));

        assertThat(customData(((StaticBlock) decorated.blocks().getFirst()).content()))
                .extracting(entryMap -> entryMap.get("value"))
                .containsExactly("Liverpool Santa Fe", 7);
        assertThat(customData(decorated.entryMetadata()))
                .extracting(entryMap -> entryMap.get("value"))
                .containsExactly("Liverpool Santa Fe");
    }

    @Test
    void withoutAStore_storeEntriesAreLeftAsAuthored() {
        List<LandingBlock> blocks = List.of(block("b1", events(bff("selected_store.name"))));

        BffEventDecorator.Decorated decorated = decorator.decorate(blocks, Map.of(), null);

        assertThat(decorated.blocks()).isSameAs(blocks);
    }

    /** digital_bff processed the page-level events after populating every block list. */
    @Test
    void pageEvents_areNumberedAfterEveryBlock() {
        List<LandingBlock> blocks = List.of(block("b1", events(bff("index"))), block("b2", events(bff("index"))));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("events", events(bff("index")));
        entry.put("footer", Map.of("events", events(bff("index"))));

        BffEventDecorator.Decorated decorated = decorator.decorate(blocks, entry, null);

        assertThat(customData(decorated.entryMetadata()).getFirst()).containsEntry("value", 3);
        // Only the page's own events list is decorated — never the header/footer it carries.
        @SuppressWarnings("unchecked")
        Map<String, Object> footer = (Map<String, Object>) decorated.entryMetadata().get("footer");
        assertThat(customData(footer).getFirst()).doesNotContainKey("value");
    }

    @Test
    void nullInputs_areAnEmptyPage() {
        BffEventDecorator.Decorated decorated = decorator.decorate(null, null, null);

        assertThat(decorated.blocks()).isEmpty();
        assertThat(decorated.entryMetadata()).isEmpty();
    }

    @Test
    void pageEventsOfTheWrongShape_areLeftAlone() {
        List<LandingBlock> blocks = List.of(block("b1", events(bff("index"))));
        Map<String, Object> entry = Map.of("events", "not-a-list");

        assertThat(decorator.decorate(blocks, entry, null).entryMetadata()).isSameAs(entry);
    }

    private static StaticBlock block(String uid, List<Object> events) {
        return new StaticBlock(uid, BlockType.CONTAINER, new LinkedHashMap<>(Map.of("uid", uid, "events", events)));
    }

    private static List<Object> events(Map<String, Object>... customData) {
        return new ArrayList<>(List.of(new LinkedHashMap<>(Map.of(
                "name", "select_item", "customData", new ArrayList<>(List.of(customData))))));
    }

    private static Map<String, Object> bff(String type) {
        return new LinkedHashMap<>(Map.of("name", "n-" + type, "type", type, "source", "bff"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> customData(Map<String, Object> holder) {
        List<Object> events = (List<Object>) holder.get("events");
        return (List<Map<String, Object>>) ((Map<String, Object>) events.getFirst()).get("customData");
    }
}
