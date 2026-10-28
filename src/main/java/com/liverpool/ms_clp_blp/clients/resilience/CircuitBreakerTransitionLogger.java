package com.liverpool.ms_clp_blp.clients.resilience;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;

/**
 * Logs every circuit-breaker state change — the timestamped "the breaker just flipped" fact a
 * post-mortem needs, which a rise in rejected calls only lets you infer.
 *
 * <h3>Why the registry's own publisher</h3>
 * <p>The idiomatic {@code RegistryEventConsumer} bean is collected by
 * {@code resilience4j-spring-boot3}, which this project deliberately excludes in favour of building
 * {@code CircuitBreakerRegistry.of(config)} by hand. A consumer bean would compile, publish nothing,
 * and fail silently. Subscribing to the registry's event publisher is the mechanism that applies.</p>
 *
 * <h3>Subscribe, then sweep</h3>
 * <p>In that order, with a name-keyed dedup set. Breakers are created lazily, some in bean
 * constructors that may run before or after this one; the listener catches every breaker created from
 * now on, the sweep catches every one created before, and the set makes the overlap harmless.</p>
 *
 * <p>Events are published synchronously on the thread that caused the transition, so nothing here may
 * throw out to the caller.</p>
 */
@Component
public class CircuitBreakerTransitionLogger {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);

    private final Set<String> attached = ConcurrentHashMap.newKeySet();

    /**
     * @param registry the shared registry every breaker in the service comes from
     */
    public CircuitBreakerTransitionLogger(CircuitBreakerRegistry registry) {
        registry.getEventPublisher().onEntryAdded(event -> attach(event.getAddedEntry()));
        registry.getAllCircuitBreakers().forEach(this::attach);
    }

    /**
     * Subscribes to one breaker's transitions, at most once.
     *
     * @param breaker the breaker to observe
     */
    private void attach(CircuitBreaker breaker) {
        if (attached.add(breaker.getName())) {
            breaker.getEventPublisher().onStateTransition(event -> logTransition(breaker, event));
        }
    }

    /**
     * Emits one structured line with the sliding-window snapshot that explains the change — what turns
     * "the breaker opened" into "the breaker opened because 62 of 80 calls failed".
     *
     * @param breaker the breaker that changed state
     * @param event   the transition
     */
    private static void logTransition(CircuitBreaker breaker, CircuitBreakerOnStateTransitionEvent event) {
        try {
            CircuitBreaker.Metrics metrics = breaker.getMetrics();
            LogEvents.at(log, LogEvents.CIRCUIT_BREAKER_STATE_CHANGED)
                    .addKeyValue(LogFields.BREAKER, breaker.getName())
                    .addKeyValue(LogFields.BREAKER_FROM_STATE, event.getStateTransition().getFromState())
                    .addKeyValue(LogFields.BREAKER_TO_STATE, event.getStateTransition().getToState())
                    .addKeyValue(LogFields.BREAKER_FAILURE_RATE, metrics.getFailureRate())
                    .addKeyValue(LogFields.BREAKER_FAILED_CALLS, metrics.getNumberOfFailedCalls())
                    .addKeyValue(LogFields.BREAKER_SLOW_CALLS, metrics.getNumberOfSlowCalls())
                    .addKeyValue(LogFields.BREAKER_BUFFERED_CALLS, metrics.getNumberOfBufferedCalls())
                    .log("Circuit breaker changed state");
        } catch (RuntimeException ignored) {
            // Deliberately swallowed: this runs inline on the caller's thread and is observability only.
        }
    }
}
