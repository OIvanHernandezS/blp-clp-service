package com.liverpool.ms_clp_blp.clients.vertex;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import com.liverpool.dto.PlpRequestPayloadDTO;
import com.liverpool.ms_clp_blp.clients.vertex.dto.VertexSearchResponse;

/**
 * Declarative client for {@code ms_plp_search}'s Vertex AI category search.
 *
 * <p>HTTP Basic credentials, timeouts and the connection pool come from {@link VertexFeignConfig}, so
 * no call site handles authentication. This interface carries no retry logic: the circuit breaker is
 * applied at the use-case layer and retries are deliberately absent (ADR-004).</p>
 */
@FeignClient(
		name = "vertexClient",
		url = "${vertex.base-url}",
		configuration = VertexFeignConfig.class)
public interface VertexClient {

	/**
	 * Runs a category search.
	 *
	 * @param request category id, page size, brand and channel
	 * @return the search envelope; {@code data} may be {@code null} when nothing matched
	 */
	@PostMapping(path = "${vertex.search-path}", consumes = MediaType.APPLICATION_JSON_VALUE)
	VertexSearchResponse search(@RequestBody PlpRequestPayloadDTO request);
}
