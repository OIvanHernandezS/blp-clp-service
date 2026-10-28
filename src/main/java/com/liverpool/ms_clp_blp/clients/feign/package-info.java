/**
 * Cross-client Feign configuration — the logging beans every {@code @FeignClient} inherits.
 *
 * <p>Only configuration that must apply to <em>all</em> clients belongs here. Per-system transport
 * tuning (timeouts, connection pools, the HTTP transport itself) lives in the owning
 * {@code clients/<system>/} package as an un-annotated {@code *FeignConfig} class referenced from
 * {@code @FeignClient(configuration = ...)}, so its beans stay scoped to that one client.</p>
 *
 * <p>No ports, no domain types, no slice knowledge. Must never depend on {@code landing}
 * (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients.feign;
