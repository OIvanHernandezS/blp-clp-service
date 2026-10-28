package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;

import java.util.Map;

/**
 * Reads the few fields a purge decision needs out of a Contentstack webhook delivery.
 *
 * <p>Every accessor tolerates a payload that is empty, shaped differently, or missing any level of
 * nesting, and answers {@code null} rather than throwing. The alternative — letting a
 * {@code ClassCastException} or an NPE escape — would turn a malformed delivery into a 500, and
 * Contentstack retries every non-2xx.</p>
 *
 * <p>The content type is looked for in three positions because Contentstack has delivered it in all
 * three, depending on the stack and the event. {@code landing.brandflags} does the same thing for the same
 * reason; the logic is duplicated rather than shared because {@code shared} may never import a slice
 * (ArchitectureTest), and a webhook parser is not worth inverting a dependency for.</p>
 */
final class ContentstackPayload {

    private static final String KEY_EVENT = "event";
    private static final String KEY_DATA = "data";
    private static final String KEY_ENTRY = "entry";
    private static final String KEY_UID = "uid";
    private static final String KEY_LOCALE = "locale";
    private static final String KEY_CONTENT_TYPE_UID = "content_type_uid";
    private static final String KEY_CONTENT_TYPE = "content_type";

    private ContentstackPayload() {
    }

    /**
     * Reads the content type from any of the three positions Contentstack has used for it: top level,
     * inside {@code data}, or as {@code data.content_type.uid}.
     *
     * @param payload the delivery body
     * @return the content type uid, or {@code null} when the delivery names none
     */
    static String contentTypeUid(Map<String, Object> payload) {
        String direct = stringValue(payload, KEY_CONTENT_TYPE_UID);
        if (direct != null) {
            return direct;
        }
        Map<String, Object> data = mapValue(payload, KEY_DATA);
        if (data == null) {
            return null;
        }
        String nested = stringValue(data, KEY_CONTENT_TYPE_UID);
        if (nested != null) {
            return nested;
        }
        return stringValue(mapValue(data, KEY_CONTENT_TYPE), KEY_UID);
    }

    /**
     * @param payload the delivery body
     * @return the event that triggered the delivery, e.g. {@code publish}, or {@code null}
     */
    static String event(Map<String, Object> payload) {
        return stringValue(payload, KEY_EVENT);
    }

    /**
     * Logged, never acted on: it is what ties a purge back to the entry an editor actually saved.
     *
     * @param payload the delivery body
     * @return {@code data.entry.uid}, or {@code null}
     */
    static String entryUid(Map<String, Object> payload) {
        return stringValue(mapValue(mapValue(payload, KEY_DATA), KEY_ENTRY), KEY_UID);
    }

    /**
     * Logged, never acted on: the purge covers every locale, because the cached endpoint responses are
     * not split by one.
     *
     * @param payload the delivery body
     * @return {@code data.entry.locale}, or {@code null}
     */
    static String locale(Map<String, Object> payload) {
        return stringValue(mapValue(mapValue(payload, KEY_DATA), KEY_ENTRY), KEY_LOCALE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String stringValue(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
