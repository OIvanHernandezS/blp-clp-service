package com.liverpool.ms_clp_blp.clients.vertex.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Minimal projection of {@code ms_plp_search}'s {@code ApiResponseDTO} envelope — only the
 * {@code records} the products_list adapter needs.
 *
 * <p>{@code ApiResponseDTO.data} is typed {@code Object} in {@code platform-commons}, which defeats
 * direct typed deserialization, and the full {@code PlpResponseDto} shape is local to
 * {@code ms_plp_search} and unpublished — so this narrow shape is defined here rather than depending
 * on either.</p>
 *
 * @param data search payload wrapper, {@code null} when the upstream returns an empty envelope
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VertexSearchResponse(VertexSearchData data) {
}
