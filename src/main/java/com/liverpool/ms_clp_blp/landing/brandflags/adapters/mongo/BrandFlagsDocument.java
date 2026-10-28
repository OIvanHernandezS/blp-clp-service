package com.liverpool.ms_clp_blp.landing.brandflags.adapters.mongo;

import java.time.Instant;
import java.util.Map;

/**
 * Persisted shape of {@link com.liverpool.ms_clp_blp.landing.models.BrandFlags}.
 *
 * <p>Separate from the domain record for two reasons. The obvious one is the ArchUnit rule that keeps
 * {@code models/} free of framework types. The load-bearing one is that {@code _id} is a storage
 * concept: the domain knows a brand, while the store needs {@code <BRAND>_<PROFILE>} so a single
 * cluster can hold QA and production side by side without either reading the other's flags.</p>
 *
 * @param id        {@code <BRAND>_<PROFILE>}, e.g. {@code LP_QA}
 * @param brand     brand identifier in upper case
 * @param profile   deployment profile in upper case
 * @param flags     flag id to value; may omit any flag, which then falls back to the YAML
 * @param entryUid  Contentstack entry this came from, for tracing a value back to its author
 * @param updatedAt when the webhook captured it
 * @param source    what wrote the document: {@code contentstack} for a CMS-authored publish, or
 *                  {@code default-seed} for a snapshot written because the brand had no document yet
 * @param settings  numeric setting id to value, edited by hand in MongoDB and never written by this
 *                  service; {@code Object} values because the shell stores a typed {@code 3} as a
 *                  double. May omit any setting, which then falls back to its configured value
 */
public record BrandFlagsDocument(
        String id,
        String brand,
        String profile,
        Map<String, Boolean> flags,
        String entryUid,
        Instant updatedAt,
        String source,
        Map<String, Object> settings) {

    /** Collection field name for the primary key, used when building queries. */
    static final String FIELD_ID = "_id";

    /** Written by a real Contentstack publish/unpublish. */
    static final String SOURCE_CONTENTSTACK = "contentstack";

    /** Written because the brand had no document yet; carries a {@code landing.feature-flags} snapshot. */
    static final String SOURCE_DEFAULT_SEED = "default-seed";

    /** Separator between brand and profile in {@link #id}. */
    private static final String ID_SEPARATOR = "_";

    /**
     * @param brand   brand identifier (any case)
     * @param profile deployment profile (any case)
     * @return the document id both the webhook writer and the request-path reader must agree on
     */
    static String idFor(String brand, String profile) {
        return brand + ID_SEPARATOR + profile;
    }
}
