package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code GET /categories}: one category with its visible children, rendered by
 * {@link CategoryDataMapper} exactly as the {@code categoryData} object of a landing page (same keys,
 * same order, same {@code metadata} on every node).
 *
 * <p>{@link JsonValue} makes Jackson serialise this record directly as {@link #root}, so the wrapper is
 * invisible in the JSON while keeping a named DTO at the controller boundary — the same device as
 * {@link LandingPageResponse}.</p>
 *
 * @param root the category as an ordered map ({@code id … children}, then {@code breadcrumb})
 */
@Schema(type = "object",
        description = "The category and its visible children, in the categoryData shape of the landing "
                + "pages. Every node carries metadata (imageUrl, categoryImage, viewUrl, showpath, "
                + "applyCLPRedirect, …). Serialised directly as the root object.")
public record CategoryResponse(Map<String, Object> root) {

    @JsonValue
    @Override
    public Map<String, Object> root() {
        return root;
    }
}
