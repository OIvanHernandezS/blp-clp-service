package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A raw, unclassified block as delivered by Contentstack, before the composition service decides
 * whether it resolves statically or becomes a dynamic placeholder.
 *
 * @param uid            Contentstack block uid ({@code _uid})
 * @param contentTypeUid Contentstack content type ({@code _content_type_uid})
 * @param audienceFilter raw {@code audience_filter} value (may be null)
 * @param enabledOnWeb   whether the block is enabled for web
 * @param enabledOnApps  whether the block is enabled for apps
 * @param attributes     remaining raw block attributes
 */
public record BlockDefinition(
        String uid,
        String contentTypeUid,
        String audienceFilter,
        boolean enabledOnWeb,
        boolean enabledOnApps,
        Map<String, Object> attributes) {

    /** Contentstack field naming the backing system of a dynamic block. */
    private static final String ATTR_SOURCE_OF_DATA = "source_of_data";

    private static final String KEY_UID = "uid";
    private static final String KEY_UID_LEGACY = "_uid";
    private static final String KEY_CONTENT_TYPE_UID = "_content_type_uid";
    private static final String KEY_AUDIENCE_FILTER = "audience_filter";
    private static final String KEY_ENABLE_ON_WEB = "enable_on_web";
    private static final String KEY_ENABLE_ON_APPS = "enable_on_apps";

    public BlockDefinition {
        // Null-tolerant unmodifiable copy: raw CMS blocks carry null-valued optional fields, which
        // Map.copyOf rejects with an NPE. Insertion order is preserved for response fidelity.
        attributes = attributes == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /**
     * The block's backing system, read from the raw {@code source_of_data} attribute.
     *
     * <p>Derived rather than promoted to a record component: it is only meaningful for dynamic blocks
     * and must stay in {@link #attributes} regardless, since the response echoes the CMS fields
     * verbatim.</p>
     *
     * @return the parsed source, {@link SourceOfData#NONE} when the field is absent or blank
     */
    public SourceOfData sourceOfData() {
        return SourceOfData.from(attributes.get(ATTR_SOURCE_OF_DATA));
    }

    /**
     * Builds a definition from one flat Contentstack block map — the single definition of "how a CMS
     * map becomes a block".
     *
     * <p>It lives here rather than in the CMS adapter because there are now two callers: the adapter,
     * for the page's top-level blocks, and the composition service's nested pass, for the children of a
     * container (ADR-065). A child must be read with exactly the same rules as a top-level block, or a
     * nested {@code products_list} would silently lose its {@code audience_filter} or default to
     * disabled — which is precisely the class of bug the two copies would have produced.</p>
     *
     * <p>The rules, unchanged from the adapter: production entries carry {@code uid} and legacy layout
     * entries {@code _uid} (normalised to {@code uid} so the identity key matches digital_bff for both
     * schemas); {@code enable_on_web}/{@code enable_on_apps} default to <strong>true</strong> when
     * absent, because production landing blocks omit them and channel control is implicit in the per-channel
     * CMS template; and every other field stays in {@link #attributes} verbatim, the block's own
     * {@code uid} and {@code _content_type_uid} included — they are the frontend's identity and type
     * discriminator (Rule 19).</p>
     *
     * @param raw one flat block map, already unwrapped by the payload normaliser
     * @return the unclassified definition, or {@code null} when {@code raw} is null
     */
    public static BlockDefinition fromCms(Map<String, Object> raw) {
        if (raw == null) {
            return null;
        }
        String uid = raw.containsKey(KEY_UID)
                ? asString(raw.get(KEY_UID))
                : asString(raw.get(KEY_UID_LEGACY));

        Map<String, Object> attributes = new LinkedHashMap<>(raw);
        if (!attributes.containsKey(KEY_UID) && uid != null) {
            attributes.put(KEY_UID, uid);
        }
        attributes.remove(KEY_UID_LEGACY);

        return new BlockDefinition(
                uid,
                asString(raw.get(KEY_CONTENT_TYPE_UID)),
                asString(raw.get(KEY_AUDIENCE_FILTER)),
                !raw.containsKey(KEY_ENABLE_ON_WEB) || Boolean.TRUE.equals(raw.get(KEY_ENABLE_ON_WEB)),
                !raw.containsKey(KEY_ENABLE_ON_APPS) || Boolean.TRUE.equals(raw.get(KEY_ENABLE_ON_APPS)),
                attributes);
    }

    /**
     * Reads a CMS field that is contractually a string. A non-string value is ignored rather than
     * forced, so one mistyped field in the CMS cannot fail the page with a {@code ClassCastException} —
     * the previous cast did exactly that.
     */
    private static String asString(Object value) {
        return value instanceof String s ? s : null;
    }
}
