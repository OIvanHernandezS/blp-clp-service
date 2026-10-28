package com.liverpool.ms_clp_blp.clients.contentstackdirect;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transport settings for the Contentstack Delivery API calls (Rule 6 — nothing hardcoded).
 *
 * <p>The credentials themselves are not here: they live under {@code api.content-stack} and are bound
 * by platform-commons' {@code ContentStackProperties}, which already resolves one of four stacks per
 * brand. This record covers only what ms-clp-blp owns — what a read costs in latency.</p>
 *
 * <p>There is no {@code enabled} switch any more. It existed to arm or disarm a failure-driven
 * fallback behind the content-service proxy; with Contentstack the only live source (ADR-053), a
 * switch that turned it off would only be a way to take the service down.</p>
 *
 * @param connectTimeout TCP connect timeout for the calls
 * @param callTimeout    hard ceiling on a whole call, and therefore on how long a landing page request
 *                       waits for its entry
 */
@ConfigurationProperties(prefix = "content-stack-direct")
public record ContentStackDirectProperties(
        Duration connectTimeout,
        Duration callTimeout) {
}
