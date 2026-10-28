package com.liverpool.ms_clp_blp.landing.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The category a landing page is authored for, as the storefront renders it next to the page: name,
 * URL, ancestry, megamenu metadata, visible children, breadcrumb and an optional CMS redirect
 * (Rule 19 reference: digital_bff {@code mapCategoryTransform} + {@code getCategoryData}).
 *
 * <p>The tree comes from the category indexer; the redirect from the Contentstack
 * {@code category_redirect} entry. Only the root carries {@code breadcrumb} and
 * {@code redirectContent} — children are the indexer's visible, valid sub-categories and carry
 * neither, exactly as the legacy response did.</p>
 *
 * @param id              upper-cased category id ({@code CATST26617625})
 * @param name            the category title
 * @param displayName     the category title (legacy duplicate, kept for the frontend contract)
 * @param urlAddress      the storefront URL of the category
 * @param hidden          always {@code false}: hidden categories never reach this model
 * @param ancestry        the category ids above this one, outermost first
 * @param metadata        the indexer's megamenu metadata
 * @param children        visible, valid sub-categories, in indexer order
 * @param breadcrumb      the indexer breadcrumb ({@code "home > Estee Lauder"}); {@code null} for a child
 * @param redirectContent the active CMS redirect for this category, or {@code null} when none
 */
public record CategoryData(
        String id,
        String name,
        String displayName,
        String urlAddress,
        boolean hidden,
        List<String> ancestry,
        CategoryMetadata metadata,
        List<CategoryData> children,
        String breadcrumb,
        Map<String, Object> redirectContent) {

    public CategoryData {
        ancestry = ancestry == null ? List.of() : List.copyOf(ancestry);
        children = children == null ? List.of() : List.copyOf(children);
        redirectContent = redirectContent == null
                ? null : Collections.unmodifiableMap(new LinkedHashMap<>(redirectContent));
    }

    /**
     * @param redirect the active redirect to attach, or {@code null}
     * @return a copy of this category carrying the redirect
     */
    public CategoryData withRedirect(Map<String, Object> redirect) {
        return new CategoryData(id, name, displayName, urlAddress, hidden, ancestry, metadata, children,
                breadcrumb, redirect);
    }
}
