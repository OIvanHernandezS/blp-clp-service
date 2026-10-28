package com.liverpool.ms_clp_blp.landing.models;

import java.util.List;

/**
 * The block lists a landing page entry can carry, in the order they are composed and rendered.
 *
 * <p>Each section is published under its own response key, exactly as digital_bff publishes it
 * ({@code availableLayouts}: {@code layout → blocks}, {@code top_layout → top_content},
 * {@code bottom_layout → bottom_content}). The CMS has two schemas for the same idea, so each section
 * names every source key it may be authored under, first match wins: the {@code page_blp} schema
 * stores the main list in a {@code layout} global field (an object holding {@code blocks}) on the live
 * stack and as a plain {@code blocks} field elsewhere, while {@code page_clp} stores its top and bottom
 * lists directly as {@code top_content} / {@code bottom_content}.</p>
 *
 * <p>Composition runs once across all sections in this order, so the eager offset and the analytics
 * {@code index} counter are page-wide — the top content counts first, then the main blocks, then the
 * bottom content — rather than restarting per section.</p>
 */
public enum PageSection {

    /** Above the main list ({@code top_layout}, or {@code page_clp}'s {@code top_content}). */
    TOP_CONTENT("top_content", List.of("top_layout", "top_content")),

    /** The main block list ({@code layout}, or a flat {@code blocks} field). */
    BLOCKS("blocks", List.of("layout", "blocks")),

    /** Below the main list ({@code bottom_layout}, or {@code page_clp}'s {@code bottom_content}). */
    BOTTOM_CONTENT("bottom_content", List.of("bottom_layout", "bottom_content"));

    private final String responseKey;
    private final List<String> sourceKeys;

    PageSection(String responseKey, List<String> sourceKeys) {
        this.responseKey = responseKey;
        this.sourceKeys = sourceKeys;
    }

    /** @return the key this section is published under in the response */
    public String responseKey() {
        return responseKey;
    }

    /** @return the CMS entry keys this section may be authored under, in precedence order */
    public List<String> sourceKeys() {
        return sourceKeys;
    }
}
