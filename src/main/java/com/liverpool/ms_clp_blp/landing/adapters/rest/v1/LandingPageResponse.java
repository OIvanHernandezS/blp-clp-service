package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonValue;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code GET /blp/{locale}/**} and {@code GET /clp/{locale}/**}.
 *
 * <p>A thin passthrough over the raw Contentstack page entry so the response is a <strong>superset of
 * digital_bff</strong> (Rule 19): the frontend keeps consuming the exact shape it does today. The root
 * carries all entry metadata ({@code uid}, {@code _version}, {@code _content_type_uid},
 * {@code publish_details}, {@code page_title}, {@code seo}, {@code locale}, …) and a {@code template}
 * object holding the composed {@code blocks} — each block flat and keyed by {@code _content_type_uid},
 * dynamic blocks additionally advertising their lazy-resolution endpoint. Blocks stay in exact
 * Contentstack order (Rule 18).</p>
 *
 * <p>{@link JsonValue} makes Jackson serialise this record directly as {@link #root}, so the wrapper is
 * invisible in the JSON while keeping a named DTO at the controller boundary.</p>
 *
 * @param root the assembled root object (entry metadata + {@code template.blocks}) ready to serialise
 */
@Schema(type = "object",
        description = "Raw Contentstack landing entry: entry metadata, the composed blocks (and "
                + "top_content/bottom_content when authored) and categoryData (digital_bff superset). "
                + "Serialised directly as the root object.")
public record LandingPageResponse(Map<String, Object> root) {

    @JsonValue
    @Override
    public Map<String, Object> root() {
        return root;
    }
}
