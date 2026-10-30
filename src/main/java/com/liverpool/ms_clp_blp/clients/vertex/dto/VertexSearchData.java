package com.liverpool.ms_clp_blp.clients.vertex.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.liverpool.dto.plp.RecordsResponse;

/**
 * The {@code data} payload of a Vertex search response.
 *
 * <p>{@code sponsoredInfo} is skipped on every record: a sponsored product carries
 * {@code sponsoredInfo.criteo}, and platform-commons' {@code Criteo} (Lombok {@code @Builder} only,
 * no creator Jackson can use) fails the whole response's decoding. No carousel reads it.</p>
 *
 * @param records matched product records, {@code null} when the upstream omits the field
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VertexSearchData(@JsonIgnoreProperties({"sponsoredInfo"}) List<RecordsResponse> records) {
}
