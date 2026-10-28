package com.liverpool.ms_clp_blp.clients.vertex;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for {@code ms_plp_search}'s Vertex AI search endpoint.
 *
 * <p>All values are externalised — no URL, credential, or path is ever hardcoded in application logic
 * (Rule 6). Authentication is HTTP Basic against {@code ms_plp_search}'s
 * {@code spring.security.user.name}/{@code .password}, so both must be injected via environment
 * variable — no default (Rule 10).</p>
 *
 * @param baseUrl        {@code ms_plp_search} base URL, including its {@code /api} context path
 * @param searchPath     path segment for the Vertex AI search endpoint ({@code /v2/search})
 * @param username       HTTP Basic auth username (env var only — no default)
 * @param password       HTTP Basic auth password (env var only — no default)
 * @param timeout        HTTP read timeout for Vertex calls
 * @param defaultPageSize page size requested when the block sets no {@code max_of_products}
 */
@ConfigurationProperties(prefix = "vertex")
public record VertexProperties(
        String baseUrl,
        String searchPath,
        String username,
        String password,
        Duration timeout,
        int defaultPageSize) {
}
