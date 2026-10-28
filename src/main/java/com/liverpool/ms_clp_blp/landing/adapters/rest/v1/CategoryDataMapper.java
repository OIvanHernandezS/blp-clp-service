package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.CategoryMetadata;

/**
 * Renders a {@link CategoryData} as the {@code categoryData} object digital_bff published, key for key
 * and in the same order (Rule 19 reference: {@code mapCategoryTransform} + {@code getCategoryData}).
 *
 * <p>Built as an ordered map rather than serialised from the record so the domain stays free of
 * JSON annotations and the wire order is spelled out in one place: {@code id … children}, then
 * {@code redirectContent} when a redirect is active, then {@code breadcrumb} on the root only.
 * {@code metadata.redirects} is always an empty array, as it always was.</p>
 */
@Component
public class CategoryDataMapper {

    /**
     * @param category the page's category
     * @return the ordered map for the response
     */
    public Map<String, Object> toMap(CategoryData category) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", category.id());
        out.put("name", category.name());
        out.put("displayName", category.displayName());
        out.put("urlAddress", category.urlAddress());
        out.put("hidden", category.hidden());
        out.put("ancestry", category.ancestry());
        out.put("metadata", metadata(category.metadata()));
        out.put("children", category.children().stream().map(this::toMap).toList());
        if (category.redirectContent() != null) {
            out.put("redirectContent", category.redirectContent());
        }
        if (category.breadcrumb() != null) {
            out.put("breadcrumb", category.breadcrumb());
        }
        return out;
    }

    private static Map<String, Object> metadata(CategoryMetadata metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (metadata == null) {
            return out;
        }
        out.put("level", metadata.level());
        out.put("parent", metadata.parent());
        out.put("title", metadata.title());
        out.put("isBrand", metadata.isBrand());
        out.put("viewUrl", metadata.viewUrl());
        out.put("imageUrl", metadata.imageUrl());
        out.put("showpath", metadata.showpath());
        out.put("ancestors", metadata.ancestors());
        out.put("groupType", metadata.groupType());
        out.put("redirects", List.of());
        out.put("rootIndex", metadata.rootIndex());
        out.put("categoryId", metadata.categoryId());
        out.put("parentTitle", metadata.parentTitle());
        out.put("parentShowpath", metadata.parentShowpath());
        out.put("gp", metadata.gp());
        out.put("gpTitle", metadata.gpTitle());
        out.put("gpShowpath", metadata.gpShowpath());
        out.put("rootDisplay", metadata.rootDisplay());
        out.put("categoryImage", metadata.categoryImage());
        out.put("displayMegaMenu", metadata.displayMegaMenu());
        out.put("applyCLPRedirect", metadata.applyClpRedirect());
        return out;
    }
}
