package com.liverpool.ms_clp_blp.clients.resilience;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Circuit-breaker tuning shared by every endpoint and outbound integration.
 *
 * <p>Per the resilience requirement: a low {@code failureRateThreshold} (5%) and <strong>no
 * retries</strong>. Retries are intentionally absent — a failing dependency should trip the breaker
 * and fall back fast rather than amplify load.</p>
 *
 * @param failureRateThreshold          percentage of failures (0-100) that opens the breaker
 * @param slidingWindowSize             number of calls evaluated for the failure rate
 * @param minimumNumberOfCalls          minimum calls before the rate is computed
 * @param waitDurationInOpenState       time the breaker stays open before probing half-open
 * @param permittedCallsInHalfOpenState probe calls allowed while half-open
 */
@ConfigurationProperties(prefix = "resilience.circuit-breaker")
public record ResilienceProperties(
        float failureRateThreshold,
        int slidingWindowSize,
        int minimumNumberOfCalls,
        Duration waitDurationInOpenState,
        int permittedCallsInHalfOpenState) {
}
