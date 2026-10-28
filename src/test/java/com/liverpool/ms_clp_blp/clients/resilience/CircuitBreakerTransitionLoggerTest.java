package com.liverpool.ms_clp_blp.clients.resilience;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTransitionLoggerTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(CircuitBreakerTransitionLogger.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    /** Breakers created before and after the logger are both observed, each exactly once. */
    @Test
    void logsEveryTransition_ofBreakersCreatedBeforeAndAfter() {
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        CircuitBreaker before = registry.circuitBreaker("category-indexer");
        new CircuitBreakerTransitionLogger(registry);
        CircuitBreaker after = registry.circuitBreaker("store-service");

        before.transitionToOpenState();
        after.transitionToForcedOpenState();

        List<Map<String, Object>> lines =
                appender.list.stream().map(CircuitBreakerTransitionLoggerTest::keyValues).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(line -> line.get("breaker"))
                .containsExactly("category-indexer", "store-service");
        assertThat(lines.getFirst())
                .containsEntry("event", "CIRCUIT_BREAKER_STATE_CHANGED")
                .containsEntry("breakerToState", CircuitBreaker.State.OPEN);
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        return pairs == null ? Map.of() : pairs.stream()
                .collect(java.util.stream.Collectors.toMap(p -> p.key, p -> p.value == null ? "" : p.value));
    }
}
