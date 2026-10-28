package com.liverpool.ms_clp_blp.clients.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Builds the shared {@link CircuitBreakerRegistry} used programmatically by adapters and use cases.
 *
 * <p>Core Resilience4j is used directly (not the Spring-AOP annotation starter) because the Spring
 * Boot 4.1 module layout does not ship an AOP starter; programmatic decoration keeps resilience
 * decoupled from the framework version and explicit at each call site. No retry policy is configured
 * — breakers fail fast on a 5% failure rate (see {@link ResilienceProperties}).</p>
 */
@Configuration
public class Resilience4jConfig {

    /**
     * Creates the registry from externalised tuning so all breakers share consistent behaviour.
     *
     * @param props circuit-breaker tuning bound from configuration
     * @return a registry pre-configured with the 5%-threshold, no-retry default
     */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(ResilienceProperties props) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.failureRateThreshold())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumNumberOfCalls())
                .waitDurationInOpenState(props.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    /**
     * Exposes per-breaker state and call metrics to Micrometer for observability (Rule 9).
     *
     * @param registry      the circuit-breaker registry to observe
     * @param meterRegistry the Micrometer registry provided by Actuator
     * @return the bound metrics binder
     */
    @Bean
    public TaggedCircuitBreakerMetrics circuitBreakerMetrics(CircuitBreakerRegistry registry,
                                                             MeterRegistry meterRegistry) {
        TaggedCircuitBreakerMetrics metrics = TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
        metrics.bindTo(meterRegistry);
        return metrics;
    }
}
