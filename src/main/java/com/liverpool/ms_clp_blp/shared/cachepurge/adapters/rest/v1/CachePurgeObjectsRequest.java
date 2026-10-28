package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.rest.v1;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /ops/cache-purge/objects}: exactly what to purge.
 *
 * <p>Unknown properties are ignored on purpose. The body ms-akamai-client takes also carries
 * {@code site}, {@code channel} and {@code alias}; ms-clp-blp has a single EdgeGrid credential, so those
 * mean nothing here, but the same JSON must still be accepted as-is.</p>
 *
 * @param type {@code invalidate} or {@code delete}; the configured default when absent
 * @param urls absolute URLs on an allowed host, or paths relative to the configured site
 * @param tags cache tags, limited to the configured ones
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "What to purge. `site`, `channel` and `alias` are accepted and ignored.")
public record CachePurgeObjectsRequest(
        @Schema(description = "Purge action; defaults to `cache-purge.action`.",
                allowableValues = {"invalidate", "delete"}, example = "invalidate")
        String type,
        @Schema(description = "Absolute URLs on an allowed host (`cache-purge.allowed-hosts`), or paths "
                + "relative to `cache-purge.site-base-url`.",
                example = "[\"https://ms-crp-clp-blp-service.liverpool.com.mx/blp/es-mx/tienda/esteelauder/catst26617625\", \"/clp/es-mx/tienda/belleza/cat5020010\"]")
        List<String> urls,
        @Schema(description = "Cache tags; only values in `cache-purge.tags` are accepted.",
                example = "[\"ms-clp-blp\"]")
        List<String> tags) {
}
