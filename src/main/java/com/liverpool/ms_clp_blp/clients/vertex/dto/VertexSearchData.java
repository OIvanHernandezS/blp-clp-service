package com.liverpool.ms_clp_blp.clients.vertex.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.liverpool.dto.plp.RecordsResponse;

/**
 * The {@code data} payload of a Vertex search response.
 *
 * @param records matched product records, {@code null} when the upstream omits the field
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VertexSearchData(List<RecordsResponse> records) {
}
