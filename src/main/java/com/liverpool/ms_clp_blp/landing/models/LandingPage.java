package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A composed landing page: the entry's own fields, each section's blocks in Contentstack order, and
 * the category the page is authored for.
 *
 * @param pageType      BLP or CLP
 * @param locale        content locale
 * @param entryMetadata the raw entry fields, with the page-level {@code events} decorated
 * @param sections      the composed blocks per section; only sections the entry authored are present
 * @param categoryData  the category the page belongs to, or {@code null} when unavailable
 */
public record LandingPage(PageType pageType, String locale, Map<String, Object> entryMetadata,
                          Map<PageSection, List<LandingBlock>> sections, CategoryData categoryData) {

    public LandingPage {
        entryMetadata = entryMetadata == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entryMetadata));
        Map<PageSection, List<LandingBlock>> copy = new EnumMap<>(PageSection.class);
        if (sections != null) {
            sections.forEach((section, blocks) -> copy.put(section, blocks == null ? List.of() : List.copyOf(blocks)));
        }
        sections = Collections.unmodifiableMap(copy);
    }

    /**
     * @param category the category the page belongs to, or {@code null}
     * @return a copy of this page carrying the category
     */
    public LandingPage withCategoryData(CategoryData category) {
        return new LandingPage(pageType, locale, entryMetadata, sections, category);
    }

    /**
     * @return the composed main block list ({@link PageSection#BLOCKS}), or an empty list when the entry
     *         did not author it
     */
    public List<LandingBlock> blocks() {
        return blocks(PageSection.BLOCKS);
    }

    /**
     * @param section the section to read
     * @return its composed blocks, or an empty list when the entry did not author it
     */
    public List<LandingBlock> blocks(PageSection section) {
        return sections.getOrDefault(section, List.of());
    }
}
