package com.liverpool.ms_clp_blp.shared.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forces the analytics event-list fields of a raw CMS payload to be JSON arrays.
 *
 * <p>Contentstack publishes the same field in two shapes. In the captured production entry
 * {@code events} appears 51 times: 44 as a JSON array and 7 as an <strong>empty object</strong>
 * {@code {}} — nested under {@code container_greeting}, {@code band.content_list[].band} and, most
 * often, {@code button_with_event}. Nothing in ms-clp-blp types these fields (CMS content travels as
 * untyped {@code Map<String,Object>} from the {@code RestClient} straight to the response JSON), so
 * the {@code {}} used to reach the frontend verbatim, where code that iterates the list breaks.</p>
 *
 * <p>Only the <strong>empty</strong> object is rewritten. A populated object, {@code null}, a string
 * or a number is left exactly as authored: turning those into {@code []} would silently discard CMS
 * data, and the frontend's problem is the missing array, not the presence of a value.</p>
 *
 * <p>Applied at ingestion rather than in the response mappers, because Contentstack is the only
 * source of these fields — one call per CMS fetch covers the landing pages and any future endpoint
 * that reads CMS content, with no mapper left to forget. It also gives
 * {@link com.liverpool.ms_clp_blp.landing.BffEventDecorator} a uniform {@code List} to walk.</p>
 *
 * <p><strong>Mutates in place.</strong> Only ever call this on a freshly deserialised payload the
 * caller owns outright (the map handed back by {@code RestClient.body(...)}); rebuilding a page-sized
 * tree per request would be pure waste on Cloud Run. Pure otherwise — no Spring, no I/O.</p>
 *
 * <p><em>Measured cost (2026-08-11, 193 KB production entry): ~50 µs per fetch — 9.5% of what parsing
 * that same JSON already costs, and ~0.01% of a {@code GET /blp|clp/**} that answers in 410–755 ms. Six
 * empty-list allocations, ~240 bytes. Nothing here needs optimising; see ADR-037.</em></p>
 */
public final class CmsEventListNormalizer {

    /**
     * The CMS analytics event-list fields, in the order the legacy digital_bff traversal visits them.
     *
     * <p>Single source of truth for the family: {@link com.liverpool.ms_clp_blp.landing.BffEventDecorator}
     * reads it too, and <strong>the order is that class's numbering contract</strong> — every
     * {@code button_events} index on a page precedes every {@code events} index. Do not reorder.</p>
     */
    public static final List<String> EVENT_LIST_KEYS = List.of("button_events", "events", "dot_events");

    private CmsEventListNormalizer() {
    }

    /**
     * Replaces every empty-object event list in the tree with an empty array, in place.
     *
     * @param node the raw CMS payload — a map, a list, or any leaf; {@code null} is a no-op
     */
    public static void normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            normalizeMap(map);
            return;
        }
        if (node instanceof List<?> list) {
            for (Object element : list) {
                normalize(element);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void normalizeMap(Map<?, ?> map) {
        for (String key : EVENT_LIST_KEYS) {
            if (map.get(key) instanceof Map<?, ?> value && value.isEmpty()) {
                // The payload comes from Jackson, so the map is mutable and exclusively ours.
                ((Map<String, Object>) map).put(key, new ArrayList<>());
            }
        }
        for (Object value : map.values()) {
            normalize(value);
        }
    }
}
