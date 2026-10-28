/**
 * Transport for Akamai's Fast Purge v3 API — the edge-cache purge this service triggers.
 *
 * <p>Holds the {@code @FeignClient} interface, its un-annotated transport configuration, the EdgeGrid
 * request signer and the wire DTOs. Nothing here decides <em>what</em> to purge or <em>when</em>:
 * that belongs to {@code shared.cachepurge}, which owns the ports, the domain model and the operator
 * endpoint. This package only knows how to authenticate and reach Akamai.</p>
 *
 * <p>Authentication is EdgeGrid ({@code EG1-HMAC-SHA256}), not HTTP Basic — Fast Purge has no
 * username/password mode. All four credential values are environment variables with no committed
 * default; see {@code AkamaiProperties}.</p>
 *
 * <p>No ports, no domain types, no slice knowledge. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.akamai;
