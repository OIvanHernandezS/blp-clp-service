package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Flattens the raw Contentstack modular-block shape into the flat block maps the domain expects.
 *
 * <p>The content-service returns each block as a <strong>single-key wrapper</strong> keyed by its
 * content type — {@code { <content_type>: { ..., _metadata: { uid } } }} — and the block arrays live
 * one level below their layout key ({@code template.<layout>.blocks[]}). Downstream
 * ({@link ContentServiceClient#toBlockDefinition}) expects each block to already carry a flat
 * {@code _content_type_uid} and {@code uid}. This normaliser bridges the two, mirroring digital_bff's
 * {@code normalizeDoubleBlocks} (Rule 19 — read-only parity with the reference BFF) so pass-through
 * content is byte-compatible:</p>
 * <ul>
 *   <li><strong>Normal block:</strong> {@code { _content_type_uid: <key>, uid: data._metadata.uid, ...data }}.</li>
 *   <li><strong>Double-nested block</strong> (the block value itself repeats its own key): unwrap the
 *       inner {@code array[0]} merged with the remaining fields.</li>
 *   <li><strong>{@code container_grid} / {@code tabs_container}:</strong> the two double-nested
 *       containers get their child collections flattened as well.</li>
 * </ul>
 *
 * <p>Stateless; no external I/O. Kept out of {@link ContentServiceClient} so the adapter stays a thin
 * transport shell and the flattening is unit-testable in isolation.</p>
 */
@Component
public class BlockPayloadNormalizer {

    private static final String KEY_CONTENT_TYPE_UID = "_content_type_uid";
    private static final String KEY_UID = "uid";
    private static final String KEY_METADATA = "_metadata";
    private static final String KEY_BLOCK = "block";
    private static final String KEY_ITEM_GRID = "item_grid";
    private static final String KEY_ITEM_GRID_TYPE = "item_grid_type";
    private static final String KEY_TABS_MANAGER = "tabs_manager";
    private static final String KEY_TAB_BLOCKS = "tab_blocks";
    private static final String TYPE_CONTAINER_GRID = "container_grid";
    private static final String TYPE_TABS_CONTAINER = "tabs_container";

    /**
     * Flattens a list of raw single-key block wrappers into flat block maps.
     *
     * @param blocks raw wrapped blocks from {@code template.<layout>.blocks[]}
     * @return one flat map per block, preserving order; entries that are not maps are skipped
     */
    public List<Map<String, Object>> normalize(List<Map<String, Object>> blocks) {
        List<Map<String, Object>> result = new ArrayList<>(blocks.size());
        for (Map<String, Object> block : blocks) {
            if (block != null && !block.isEmpty()) {
                result.add(normalizeBlock(block));
            }
        }
        return result;
    }

    /**
     * Flattens one single-key block wrapper. Mirrors digital_bff {@code normalizeDoubleBlocks}'s
     * per-block map function.
     */
    private Map<String, Object> normalizeBlock(Map<String, Object> block) {
        // Already-flat block (legacy template.blocks[] shape or a re-normalised entry): leave as-is.
        if (block.containsKey(KEY_CONTENT_TYPE_UID)) {
            return block;
        }
        Map.Entry<String, Object> entry = block.entrySet().iterator().next();
        String id = entry.getKey();
        Object data = entry.getValue();

        // Double-nested: the block value itself carries a key equal to its content type.
        if (data instanceof Map<?, ?> dataMap && dataMap.containsKey(id)) {
            Map<String, Object> typed = asStringMap(dataMap);
            Map<String, Object> rest = new LinkedHashMap<>(typed);
            Object nested = rest.remove(id);

            Map<String, Object> merged = new LinkedHashMap<>();
            merged.putAll(firstElement(nested)); // nestedData[0]
            merged.putAll(rest);                 // rest overrides, matching {...nestedData[0], ...rest}

            if (TYPE_CONTAINER_GRID.equals(id)) {
                return flattenContainerGrid(merged);
            }
            if (TYPE_TABS_CONTAINER.equals(id)) {
                return flattenTabsContainer(merged);
            }
            return merged;
        }

        // Normal block: lift the content type and metadata uid onto the flat map.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_CONTENT_TYPE_UID, id);
        result.put(KEY_UID, metadataUid(data));
        if (data instanceof Map<?, ?> m) {
            result.putAll(asStringMap(m)); // ...data — real fields win over the seeded defaults
        }
        return result;
    }

    /**
     * container_grid: flatten each {@code item_grid[]} entry (itself a single-key wrapper) into a flat
     * item carrying {@code item_grid_type} plus the unwrapped {@code block[0]} and its siblings.
     */
    private Map<String, Object> flattenContainerGrid(Map<String, Object> data) {
        Object itemGrid = data.get(KEY_ITEM_GRID);
        if (!(itemGrid instanceof List<?> gridItems)) {
            return data;
        }
        List<Map<String, Object>> flattened = new ArrayList<>(gridItems.size());
        for (Object gridItem : gridItems) {
            if (!(gridItem instanceof Map<?, ?> gridMap) || gridMap.isEmpty()) {
                continue;
            }
            Map<String, Object> typed = asStringMap(gridMap);
            Map.Entry<String, Object> entry = typed.entrySet().iterator().next();
            String itemType = entry.getKey();
            Map<String, Object> itemData = entry.getValue() instanceof Map<?, ?> m
                    ? new LinkedHashMap<>(asStringMap(m))
                    : new LinkedHashMap<>();
            Object block = itemData.remove(KEY_BLOCK);

            Map<String, Object> flatItem = new LinkedHashMap<>();
            flatItem.put(KEY_ITEM_GRID_TYPE, itemType);
            flatItem.putAll(firstElement(block)); // ...block[0]
            flatItem.putAll(itemData);            // ...rest
            flattened.add(flatItem);
        }
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put(KEY_ITEM_GRID, flattened);
        return result;
    }

    /**
     * tabs_container: recursively flatten each tab's {@code tab_blocks[]} through the same block
     * normalisation so nested blocks share the flat shape.
     */
    private Map<String, Object> flattenTabsContainer(Map<String, Object> data) {
        Object tabsManager = data.get(KEY_TABS_MANAGER);
        if (!(tabsManager instanceof List<?> tabs)) {
            return data;
        }
        List<Map<String, Object>> normalizedTabs = new ArrayList<>(tabs.size());
        for (Object tab : tabs) {
            if (!(tab instanceof Map<?, ?> tabMap)) {
                continue;
            }
            Map<String, Object> tabItem = new LinkedHashMap<>(asStringMap(tabMap));
            Object tabBlocks = tabItem.get(KEY_TAB_BLOCKS);
            if (tabBlocks instanceof List<?> list) {
                tabItem.put(KEY_TAB_BLOCKS, normalize(asMapList(list)));
            }
            normalizedTabs.add(tabItem);
        }
        Map<String, Object> result = new LinkedHashMap<>(data);
        result.put(KEY_TABS_MANAGER, normalizedTabs);
        return result;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Reads {@code data._metadata.uid}; {@code null} when the shape is absent or not a string. */
    private Object metadataUid(Object data) {
        if (data instanceof Map<?, ?> m && m.get(KEY_METADATA) instanceof Map<?, ?> metadata) {
            return metadata.get(KEY_UID);
        }
        return null;
    }

    /** Returns the first element of a list as a string-keyed map, or an empty map. */
    private Map<String, Object> firstElement(Object value) {
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> first) {
            return asStringMap(first);
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asStringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private List<Map<String, Object>> asMapList(List<?> list) {
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add(asStringMap(m));
            }
        }
        return result;
    }
}
