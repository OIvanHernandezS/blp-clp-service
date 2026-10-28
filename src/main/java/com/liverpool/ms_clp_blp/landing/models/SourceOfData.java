package com.liverpool.ms_clp_blp.landing.models;

import java.util.Arrays;
import java.util.Locale;

/**
 * Backing system a dynamic block draws its content from, read from the Contentstack
 * {@code source_of_data} field.
 *
 * <p>{@code _content_type_uid} alone does not identify which service resolves a block: a live landing page
 * template ships several {@code products_list} blocks that are backed by different systems and are
 * distinguished only by this field. Routing on the content type alone sends Jewel-backed carousels to
 * the Salesforce adapter, so this enum is the second half of {@link BlockResolutionKey}.</p>
 *
 * <p>Only the sources ms-clp-blp actually implements are modelled. Anything else — {@code lob} (ADR-015,
 * blocked), {@code recently_viewed} — maps to {@link #UNSUPPORTED}, which deliberately has
 * <strong>no</strong> catalog entry so the block falls back to static pass-through instead of being
 * silently misrouted to whichever adapter happens to share its content type.</p>
 */
public enum SourceOfData {

    /** Field absent or blank — the block has a single, unambiguous backing service. */
    NONE(null),

    /** JewelML recommendation models ({@code jewel_model_config}). */
    JEWEL("jewel"),

    /** Salesforce Evergage carousels ({@code salesforce_carousel}). */
    SALESFORCE("salesforce"),

    /** Salesforce Personalization (V2) decisions ({@code salesforce_v2_config}). */
    SALESFORCE_V2("salesforce_v2"),

    /** Vertex AI category search, via {@code ms_plp_search}'s {@code POST /v2/search} (ADR-021). */
    VERTEX("vertex"),

    /**
     * Legacy GroupBy-tagged carousels, aliased to the Vertex provider rather than left unmapped
     * (ADR-055, supersedes ADR-014's exclusion for this one source). Routes identically to
     * {@link #VERTEX} everywhere downstream: {@code VertexSettings.source()} always answers
     * {@code VERTEX}, so the circuit breaker, feature flag and adapter never see the original token.
     */
    GROUPBY("groupby"),

    /** A source present in the CMS that this service does not implement; never routed. */
    UNSUPPORTED(null);

    private final String token;

    SourceOfData(String token) {
        this.token = token;
    }

    /**
     * @return the Contentstack {@code source_of_data} token, or {@code null} for the synthetic values
     */
    public String token() {
        return token;
    }

    /**
     * Resolves a source from a raw CMS attribute value.
     *
     * @param raw the {@code source_of_data} attribute (may be null, blank, or a non-string)
     * @return {@link #NONE} when absent/blank, the matching source, or {@link #UNSUPPORTED}
     */
    public static SourceOfData from(Object raw) {
        if (!(raw instanceof String value) || value.isBlank()) {
            return NONE;
        }
        String needle = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(source -> source.token != null && source.token.equals(needle))
                .findFirst()
                .orElse(UNSUPPORTED);
    }
}
