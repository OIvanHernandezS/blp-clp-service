/**
 * Turns a Contentstack publish into an edge-cache purge, with no operator in the loop.
 *
 * <p>{@code shared.cachepurge} gave an operator a button to press. This package presses it: when an
 * editor publishes a {@code page} or {@code flex} entry, the Akamai edge keeps serving the previous
 * landing page until its TTL expires, and the only remedy was for someone to notice and run
 * {@code POST /ops/cache-purge/paths} by hand.</p>
 *
 * <h3>Why it lives in {@code shared}</h3>
 * <p>It purges every cached landing page — BLP and CLP alike, through the service's cache tag — so it
 * belongs to no single slice, exactly the argument {@code shared.cachepurge} already makes. It is a separate package from that one rather than a second
 * controller inside it because it owns a policy of its own: which content types matter, how a failure
 * is reported, and how a bulk publish is collapsed into one purge. {@code cachepurge} stays the
 * mechanism; this is one caller of it.</p>
 *
 * <h3>The flow</h3>
 * <p>{@code POST /webhooks/contentstack/cache-purge} → {@code CmsPurgeWebhookUseCases} decides whether
 * the delivery is relevant → {@code CachePurgeService.purgeAll(...)}, the same inbound port
 * {@code CachePurgeOpsControllerV1} drives → {@code AkamaiCachePurgeAdapter} → {@code clients.akamai}.
 * Nothing about the purge itself is reimplemented here.</p>
 *
 * <h3>Three properties worth knowing</h3>
 * <p><strong>It always answers 200.</strong> Contentstack retries every non-2xx, so an irrelevant
 * delivery — or a purge Akamai refused — reported as a failure becomes a retry loop. The outcome is in
 * the body's {@code purged} and {@code reason}, and in the log line, never in the status code.</p>
 *
 * <p><strong>Every event purges, not just {@code publish}.</strong> An unpublish or a delete leaves the
 * edge serving content that no longer exists, which is the more visible failure of the two. This is the
 * opposite of {@code landing.brandflags}, which branches on the event because its two outcomes differ.</p>
 *
 * <p><strong>It honours {@code cache-purge.enabled} itself.</strong> That kill switch is applied at the
 * HTTP boundary in {@code CachePurgeOpsControllerV1}, not inside {@code CachePurgeUseCases}, so a second
 * caller of the port does not inherit it. Without the explicit check here the webhook would purge the
 * live edge from a developer's laptop, where the {@code dev} profile sets it to {@code false}.</p>
 *
 * <p>May depend on {@code shared} and {@code clients}. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;
