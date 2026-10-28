package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The raw landing page definition read from Contentstack: the entry's own fields plus its block lists,
 * one per {@link PageSection}. Technology-free — the adapter maps the CMS JSON into this.
 *
 * <p>For digital_bff response parity (Rule 19) the raw entry is carried through verbatim in
 * {@link #entryMetadata} — {@code uid}, {@code title}, {@code url}, {@code category_id}, {@code seo},
 * {@code events}, the per-page {@code header}/{@code footer} overrides, {@code publish_details} and so
 * on — minus the section keys, which are re-emitted from {@link #sections} once composed, and minus the
 * page type's {@link PageType#strippedKeys() stripped keys}.</p>
 *
 * @param pageType      BLP or CLP
 * @param locale        content locale
 * @param categoryId    the entry's {@code category_id}; {@code null} when the entry carries none
 * @param entryMetadata the raw entry fields, in CMS order
 * @param sections      the raw block lists, flattened but not yet composed; absent sections are
 *                      absent keys, not empty lists, so the response keeps the entry's own shape
 */
public record LandingDefinition(PageType pageType, String locale, String categoryId,
                                Map<String, Object> entryMetadata,
                                Map<PageSection, List<BlockDefinition>> sections) {

    public LandingDefinition {
        // Null-tolerant copies: CMS maps carry null-valued optional fields Map.copyOf would reject.
        entryMetadata = entryMetadata == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entryMetadata));
        Map<PageSection, List<BlockDefinition>> copy = new EnumMap<>(PageSection.class);
        if (sections != null) {
            sections.forEach((section, blocks) -> copy.put(section, blocks == null ? List.of() : List.copyOf(blocks)));
        }
        sections = Collections.unmodifiableMap(copy);
    }

    /**
     * A definition carrying only the main block list — the shape most landing pages are authored in.
     *
     * @param pageType BLP or CLP
     * @param locale   content locale
     * @param blocks   the main section's blocks
     * @return a definition with no entry metadata and no category id
     */
    public static LandingDefinition ofBlocks(PageType pageType, String locale, List<BlockDefinition> blocks) {
        return new LandingDefinition(pageType, locale, null, Map.of(), Map.of(PageSection.BLOCKS, blocks));
    }

    /** @return how many blocks the entry authored across every section */
    public int blockCount() {
        return sections.values().stream().mapToInt(List::size).sum();
    }
}
