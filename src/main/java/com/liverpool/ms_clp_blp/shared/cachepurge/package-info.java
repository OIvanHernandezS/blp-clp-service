/**
 * Operator-triggered purge of this service's content from the CDN edge.
 *
 * <p>ms-clp-blp runs no cache of its own — ADR-006 is superseded and caching belongs to ContentService
 * and the Akamai edge in front of this application. That leaves one gap this package fills: when
 * content changes, or a bad response gets cached, there was no way to invalidate the edge from here
 * and the only option was to wait out the TTL.</p>
 *
 * <h3>Why it lives in {@code shared}</h3>
 * <p>A purge covers every cached landing page — through the service's {@code Edge-Cache-Tag} plus any
 * configured paths — so it belongs to no single slice. It carries the usual shape: framework-free
 * {@code models} and {@code ports} (ArchUnit enforces it), with {@code adapters} holding the two edges,
 * {@code akamai} submitting purges and {@code rest.v1} exposing the operator endpoint. It needs no slice
 * types at all: purge targets are strings from configuration.</p>
 *
 * <h3>The flow</h3>
 * <p>{@code POST /ops/cache-purge} → the kill switch and the operator's {@code action}/{@code network}
 * are resolved at the HTTP boundary → {@code CachePurgeUseCases} splits the run into groups Akamai can
 * accept (URLs and cache tags are different endpoints) → {@code AkamaiCachePurgeAdapter} submits each
 * and translates failures → {@code clients.akamai} signs and sends.</p>
 *
 * <h3>Two properties worth knowing</h3>
 * <p>Purges are <strong>asynchronous</strong>: Akamai accepting one is not Akamai having done it, and
 * {@code estimatedSeconds} is the only completion signal there is. And failures are
 * <strong>not</strong> contained, by design — a purge
 * reported as successful while half the edge still serves stale content is the failure most likely to
 * be believed.</p>
 *
 * <p>May depend on {@code shared} and {@code clients}. Must never depend on {@code landing} (enforced by ArchitectureTest).</p>
 */
package com.liverpool.ms_clp_blp.shared.cachepurge;
