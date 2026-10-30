/**
 * Low-level infrastructure for external systems — declarative Feign clients, their per-system
 * transport configuration and connection properties, grouped per system (contentstack, salesforce,
 * jewel, vertex, store, blacklist…) plus the cross-client logging beans in {@code feign}.
 *
 * <p>Each system owns a {@code <System>Client} {@code @FeignClient} interface and an un-annotated
 * {@code <System>FeignConfig} named from it, so timeouts and connection pools stay scoped to that one
 * client and a slow dependency cannot starve another's pool.</p>
 *
 * <p>No ports, no domain types, no slice knowledge: classes here are reusable plumbing that slice
 * adapters inject. Must never depend on {@code landing} (enforced by
 * ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.clients;
