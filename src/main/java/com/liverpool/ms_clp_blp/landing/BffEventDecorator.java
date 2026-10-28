package com.liverpool.ms_clp_blp.landing;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.shared.web.CmsEventListNormalizer;

/**
 * Fills in the analytics {@code customData} values that the backend owns, over the final composed
 * page (Rule 19 reference: {@code processBffCustomDataEvents} in digital_bff).
 *
 * <p>CMS authors mark a {@code customData} entry with {@code source: "bff"} to say "the backend fills
 * this in". Three types exist:</p>
 * <ul>
 *   <li>{@code index} — a <strong>page-wide running counter</strong>, starting at 1, that numbers
 *       every such entry across the whole block tree. Without it the entries ship with no
 *       {@code value} and analytics silently mis-attributes every component on the page.</li>
 *   <li>{@code selected_store.code} / {@code selected_store.name} — the shopper's favourite store.
 *       Left untouched when no store is known, exactly as digital_bff left them for a shopper
 *       without one.</li>
 * </ul>
 *
 * <p><strong>Ordering is the contract.</strong> The counter is assigned in the legacy traversal
 * order: one complete depth-first pass per event-list name over the blocks, in the order
 * {@code button_events}, {@code events}, {@code dot_events} — so every {@code button_events} index on
 * the page precedes every {@code events} index, regardless of nesting. Within a pass, an object's own
 * event list is numbered before its children are visited. Numbering depends on which blocks survived
 * composition, so this runs <em>after</em> drops and ordering are final. The entry's own page-level
 * {@code events} are numbered last, continuing the same counter — digital_bff processed them after
 * populating every block list, and only that one list (never the {@code header}/{@code footer} the
 * entry also carries).</p>
 *
 * <p>Pure and stateless — no Spring, no I/O. Blocks are rebuilt rather than mutated, since block
 * content is immutable by construction.</p>
 */
public class BffEventDecorator {

    /**
     * Event-list field names, in the order the legacy implementation passes over the tree — shared
     * with {@link CmsEventListNormalizer}, which guarantees each one arrives as a list.
     */
    private static final List<String> EVENT_LIST_NAMES = CmsEventListNormalizer.EVENT_LIST_KEYS;

    /** The one page-level event list digital_bff decorated. */
    private static final String PAGE_EVENTS = "events";

    private static final String KEY_CUSTOM_DATA = "customData";
    private static final String KEY_SOURCE = "source";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";
    private static final String SOURCE_BFF = "bff";
    private static final String TYPE_INDEX = "index";
    private static final String TYPE_STORE_NAME = "selected_store.name";
    private static final String TYPE_STORE_CODE = "selected_store.code";
    private static final int FIRST_INDEX = 1;

    /**
     * The decorated page: its blocks and its entry fields, both rebuilt.
     *
     * @param blocks        the blocks, in the order they were given
     * @param entryMetadata the entry fields with the page-level {@code events} decorated
     */
    public record Decorated(List<LandingBlock> blocks, Map<String, Object> entryMetadata) {
    }

    /**
     * Returns the blocks with every backend-owned {@code index} entry numbered and no store values.
     *
     * @param blocks the final, ordered block list
     * @return a new list with decorated content; the input is not modified
     */
    public List<LandingBlock> decorate(List<LandingBlock> blocks) {
        return decorate(blocks, Map.of(), null).blocks();
    }

    /**
     * Decorates a whole page: numbers every backend-owned {@code index} entry across the blocks, then
     * the entry's page-level {@code events}, and writes the favourite store wherever it is asked for.
     *
     * @param blocks        the final, ordered block list across every section
     * @param entryMetadata the entry's own fields; only its top-level {@code events} list is touched
     * @param store         the shopper's favourite store, or {@code null} to leave store entries unset
     * @return the decorated blocks and entry fields; the inputs are not modified
     */
    public Decorated decorate(List<LandingBlock> blocks, Map<String, Object> entryMetadata,
                              SelectedStore store) {
        List<LandingBlock> safeBlocks = blocks == null ? List.of() : blocks;
        Map<String, Object> safeMetadata = entryMetadata == null ? Map.of() : entryMetadata;

        // Two phases so the counter can follow the legacy per-event-list passes while the tree is
        // rebuilt exactly once: first decide each entry's value, then copy the tree applying them.
        Map<Object, Object> assigned = new IdentityHashMap<>();
        int[] counter = {FIRST_INDEX};
        for (String eventListName : EVENT_LIST_NAMES) {
            for (LandingBlock block : safeBlocks) {
                collect(eventListName, contentOf(block), assigned, counter, store);
            }
        }
        numberEventList(PAGE_EVENTS, safeMetadata, assigned, counter, store);
        if (assigned.isEmpty()) {
            return new Decorated(safeBlocks, safeMetadata);
        }

        List<LandingBlock> decorated = new ArrayList<>(safeBlocks.size());
        for (LandingBlock block : safeBlocks) {
            decorated.add(rebuild(block, assigned));
        }
        return new Decorated(List.copyOf(decorated), decorateMetadata(safeMetadata, assigned));
    }

    // ── phase 1: assign values in legacy traversal order ──────────────────────────────────────────

    private void collect(String eventListName, Object node, Map<Object, Object> assigned, int[] counter,
                         SelectedStore store) {
        if (node instanceof List<?> list) {
            for (Object element : list) {
                collect(eventListName, element, assigned, counter, store);
            }
            return;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return;
        }
        numberEventList(eventListName, map, assigned, counter, store);
        for (Object value : map.values()) {
            collect(eventListName, value, assigned, counter, store);
        }
    }

    private void numberEventList(String eventListName, Map<?, ?> map, Map<Object, Object> assigned,
                                 int[] counter, SelectedStore store) {
        if (map.get(eventListName) instanceof List<?> events) {
            for (Object event : events) {
                collectFromEvent(event, assigned, counter, store);
            }
        }
    }

    private void collectFromEvent(Object event, Map<Object, Object> assigned, int[] counter,
                                  SelectedStore store) {
        if (!(event instanceof Map<?, ?> eventMap)
                || !(eventMap.get(KEY_CUSTOM_DATA) instanceof List<?> customData)) {
            return;
        }
        for (Object entry : customData) {
            if (!(entry instanceof Map<?, ?> data) || !SOURCE_BFF.equals(data.get(KEY_SOURCE))
                    || assigned.containsKey(data)) {
                continue;
            }
            Object value = valueFor(data.get(KEY_TYPE), counter, store);
            if (value != null) {
                assigned.put(data, value);
            }
        }
    }

    /**
     * The value one backend-owned entry receives, or {@code null} to leave it as authored.
     */
    private static Object valueFor(Object type, int[] counter, SelectedStore store) {
        if (TYPE_INDEX.equals(type)) {
            return counter[0]++;
        }
        if (store == null) {
            return null;
        }
        if (TYPE_STORE_NAME.equals(type)) {
            return store.name();
        }
        if (TYPE_STORE_CODE.equals(type)) {
            return store.code();
        }
        return null;
    }

    // ── phase 2: rebuild the tree applying the assigned values ────────────────────────────────────

    private LandingBlock rebuild(LandingBlock block, Map<Object, Object> assigned) {
        return switch (block) {
            case StaticBlock b -> new StaticBlock(b.blockId(), b.blockType(), copyMap(b.content(), assigned));
            // msParams is carried through untouched, not copyMap'd: it is a projection of CMS *settings*
            // (model config, carousel action, thresholds), never of the event lists this decorator
            // fills in, so there is nothing in it to decorate.
            case DynamicPlaceholder b -> new DynamicPlaceholder(
                    b.blockId(), b.blockType(), copyMap(b.content(), assigned), b.resolutionPath(),
                    b.fallback(), b.featureFlagId(), b.status(), b.httpMethod(), b.loadStrategy(),
                    b.blockIdInPath(), b.msParams());
            case ResolvedBlock b -> new ResolvedBlock(
                    b.blockId(), b.blockType(), copyMap(b.csContent(), assigned), b.payload(),
                    b.resolutionPath(), b.httpMethod(), b.loadStrategy(), b.status(), b.blockIdInPath(),
                    b.msParams());
        };
    }

    /**
     * Copies the entry fields, rebuilding only the page-level {@code events} list — every other field
     * (including the {@code header}/{@code footer} overrides) is passed through by reference.
     */
    private Map<String, Object> decorateMetadata(Map<String, Object> entryMetadata,
                                                 Map<Object, Object> assigned) {
        if (!(entryMetadata.get(PAGE_EVENTS) instanceof List<?>)) {
            return entryMetadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(entryMetadata);
        copy.put(PAGE_EVENTS, copyValue(entryMetadata.get(PAGE_EVENTS), assigned));
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyMap(Map<String, Object> source, Map<Object, Object> assigned) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue(), assigned));
        }
        Object value = assigned.get(source);
        if (value != null) {
            // Appended when absent, matching the legacy assignment onto the existing entry.
            copy.put(KEY_VALUE, value);
        }
        return copy;
    }

    @SuppressWarnings("unchecked")
    private Object copyValue(Object value, Map<Object, Object> assigned) {
        if (value instanceof Map<?, ?> map) {
            return copyMap((Map<String, Object>) map, assigned);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(copyValue(element, assigned));
            }
            return copy;
        }
        return value;
    }

    private Map<String, Object> contentOf(LandingBlock block) {
        return switch (block) {
            case StaticBlock b -> b.content();
            case DynamicPlaceholder b -> b.content();
            case ResolvedBlock b -> b.csContent();
        };
    }
}
