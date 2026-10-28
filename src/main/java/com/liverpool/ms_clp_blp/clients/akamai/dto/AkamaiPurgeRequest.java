package com.liverpool.ms_clp_blp.clients.akamai.dto;

import java.util.List;

/**
 * Body of a Fast Purge v3 request: {@code {"objects": ["…", "…"]}}.
 *
 * <p>The same shape serves both purge kinds — the endpoint path, not the body, says whether the
 * entries are URLs or cache tags. For {@code /url/} they are absolute URLs
 * ({@code https://ms-crp-clp-blp-service.liverpool.com.mx/blp/es-mx/tienda/x/catst1}); for {@code /tag/} they are bare tag names
 * ({@code ms-clp-blp}).</p>
 *
 * @param objects URLs or cache tags to purge; Akamai rejects an empty array with a 400
 */
public record AkamaiPurgeRequest(List<String> objects) {
}
