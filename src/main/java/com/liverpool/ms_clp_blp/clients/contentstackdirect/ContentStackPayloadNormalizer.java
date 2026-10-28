package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Reshapes a raw Contentstack entry into the shape digital_bff received from the content-service.
 *
 * <p>The content-service applied exactly one transformation to a {@code page_blp}/{@code page_clp}
 * entry before handing it over — it replaced the {@code header} and {@code footer} reference
 * <em>arrays</em> with their first element ({@code src/app/content/service/content.service.ts}) — so
 * applying it here keeps the response's per-page header/footer overrides in the shape the storefront
 * already reads. ({@code template} is collapsed the same way for the generic {@code page} type.)</p>
 */
@Component
public class ContentStackPayloadNormalizer {

    /** The three reference fields the proxy collapses on a page entry. */
    private static final Set<String> UNWRAPPED_KEYS = Set.of("template", "header", "footer");

    /**
     * Normalises a page entry.
     *
     * @param entry the raw Contentstack entry
     * @return a copy shaped like the proxy's page response
     */
    public Map<String, Object> asProxyPage(Map<String, Object> entry) {
        if (entry == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>(entry);
        UNWRAPPED_KEYS.forEach(key -> unwrapFirst(normalized, key));
        return normalized;
    }

    /**
     * Replaces a reference array with its first element, or removes the key when the array is empty —
     * the proxy emits {@code undefined} there, which serialises to an absent key rather than to null.
     *
     * @param entry the entry being normalised, modified in place
     * @param key   the reference field to collapse
     */
    private static void unwrapFirst(Map<String, Object> entry, String key) {
        Object value = entry.get(key);
        if (!(value instanceof List<?> list)) {
            return;
        }
        if (list.isEmpty()) {
            entry.remove(key);
            return;
        }
        entry.put(key, list.get(0));
    }
}
