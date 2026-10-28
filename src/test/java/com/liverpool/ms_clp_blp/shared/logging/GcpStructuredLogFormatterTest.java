package com.liverpool.ms_clp_blp.shared.logging;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the field mapping Cloud Logging actually relies on: a top-level {@code severity} it can
 * classify (not ECS's nested {@code log.level}), so app logs stop being silently dropped by the
 * project's DEBUG/DEFAULT/INFO cost-exclusion filter.
 */
class GcpStructuredLogFormatterTest {

    private final GcpStructuredLogFormatter formatter = new GcpStructuredLogFormatter();

    @Test
    void format_mapsWarnToGcpWarningSeverity() {
        assertThat(formatter.format(event(Level.WARN, "Eager resolution failed", Map.of())))
                .contains("\"severity\":\"WARNING\"");
    }

    @Test
    void format_mapsErrorSeverity() {
        assertThat(formatter.format(event(Level.ERROR, "boom", Map.of())))
                .contains("\"severity\":\"ERROR\"");
    }

    @Test
    void format_mapsTraceAndDebugToGcpDebug() {
        assertThat(formatter.format(event(Level.TRACE, "verbose", Map.of())))
                .contains("\"severity\":\"DEBUG\"");
        assertThat(formatter.format(event(Level.DEBUG, "verbose", Map.of())))
                .contains("\"severity\":\"DEBUG\"");
    }

    @Test
    void format_includesMessageAndEndsWithNewline() {
        String line = formatter.format(event(Level.INFO, "GET /home brand=LP", Map.of()));
        assertThat(line).contains("\"message\":\"GET /home brand=LP\"").endsWith("\n");
    }

    @Test
    void format_includesSpanIdAndLabelsFromMdc() {
        String line = formatter.format(event(Level.INFO, "GET /home", Map.of(
                "spanId", "b22856f4cf5f1616",
                "requestId", "req-1",
                "correlationId", "corr-1",
                "operation", "GET /home")));

        assertThat(line)
                .contains("\"logging.googleapis.com/spanId\":\"b22856f4cf5f1616\"")
                .contains("\"logging.googleapis.com/labels\"")
                .contains("\"requestId\":\"req-1\"")
                .contains("\"correlationId\":\"corr-1\"")
                .contains("\"operation\":\"GET /home\"");
    }

    @Test
    void format_omitsTraceField_whenProjectIdNotConfigured() {
        // GCP_PROJECT_ID is unset in the test JVM, so the trace field (which requires it) must be absent
        // even though traceId is present in MDC — a partial/incorrect trace resource name is worse than none.
        String line = formatter.format(
                event(Level.INFO, "GET /home", Map.of("traceId", "6a67ec09e90ed45706a6f443cc9d5c28")));
        assertThat(line).doesNotContain("logging.googleapis.com/trace");
    }

    @Test
    void format_usesAgentSpanIdKey() {
        // The OTel Java agent injects snake_case keys; Micrometer Tracing used camelCase.
        String line = formatter.format(
                event(Level.INFO, "GET /home", Map.of("span_id", "b22856f4cf5f1616")));
        assertThat(line).contains("\"logging.googleapis.com/spanId\":\"b22856f4cf5f1616\"");
    }

    @Test
    void format_prefersAgentKeysOverMicrometerKeys() {
        // If both are ever present, the exported span's id must win: it is the only one that
        // resolves to a real trace in the backend.
        String line = formatter.format(event(Level.INFO, "GET /home", Map.of(
                "span_id", "aaaaaaaaaaaaaaaa",
                "spanId", "bbbbbbbbbbbbbbbb")));
        assertThat(line)
                .contains("\"logging.googleapis.com/spanId\":\"aaaaaaaaaaaaaaaa\"")
                .doesNotContain("bbbbbbbbbbbbbbbb");
    }

    @Test
    void format_emitsTraceSampled_fromAgentTraceFlags() throws Exception {
        JsonNode sampled = new ObjectMapper()
                .readTree(formatter.format(event(Level.INFO, "GET /home", Map.of("trace_flags", "01"))));
        assertThat(sampled.get("logging.googleapis.com/trace_sampled").asBoolean()).isTrue();

        JsonNode unsampled = new ObjectMapper()
                .readTree(formatter.format(event(Level.INFO, "GET /home", Map.of("trace_flags", "00"))));
        assertThat(unsampled.get("logging.googleapis.com/trace_sampled").asBoolean()).isFalse();
    }

    @Test
    void format_treatsUnparsableTraceFlagsAsUnsampled() throws Exception {
        // A malformed flag must never cost the log line — the descriptive payload matters more than
        // the trace link.
        JsonNode json = new ObjectMapper()
                .readTree(formatter.format(event(Level.INFO, "GET /home", Map.of("trace_flags", "zz"))));
        assertThat(json.get("logging.googleapis.com/trace_sampled").asBoolean()).isFalse();
        assertThat(json.get("message").asText()).isEqualTo("GET /home");
    }

    @Test
    void format_keepsDescriptiveFieldsAlongsideAgentTraceIds() throws Exception {
        // Regression guard: adding trace correlation must not displace or flatten the event
        // catalogue's reason fields, which are what makes a drop diagnosable in Cloud Logging.
        String line = formatter.format(event(Level.DEBUG, "Block dropped during composition",
                Map.of("trace_id", "6a67ec09e90ed45706a6f443cc9d5c28",
                        "span_id", "b22856f4cf5f1616",
                        "trace_flags", "01",
                        "requestId", "req-1",
                        "operation", "GET /home"),
                new KeyValuePair(LogFields.EVENT, "BLOCK_DROPPED"),
                new KeyValuePair(LogFields.BLOCK_ID, "blt123"),
                new KeyValuePair(LogFields.BLOCK_TYPE, "products_list"),
                new KeyValuePair(LogFields.REASON, "RESOLVER_RETURNED_EMPTY"),
                new KeyValuePair(LogFields.AUTHENTICATED, false),
                new KeyValuePair(LogFields.CHANNEL, "web"),
                new KeyValuePair(LogFields.ELAPSED_MS, 1234L)));

        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.get(LogFields.EVENT).asText()).isEqualTo("BLOCK_DROPPED");
        assertThat(json.get(LogFields.BLOCK_ID).asText()).isEqualTo("blt123");
        assertThat(json.get(LogFields.BLOCK_TYPE).asText()).isEqualTo("products_list");
        assertThat(json.get(LogFields.REASON).asText()).isEqualTo("RESOLVER_RETURNED_EMPTY");
        assertThat(json.get(LogFields.CHANNEL).asText()).isEqualTo("web");
        // Native JSON types, so Cloud Logging can compare them numerically/boolean-wise.
        assertThat(json.get(LogFields.AUTHENTICATED).isBoolean()).isTrue();
        assertThat(json.get(LogFields.ELAPSED_MS).isNumber()).isTrue();
        // ...and the trace fields coexist with them rather than replacing them.
        assertThat(json.get("logging.googleapis.com/spanId").asText()).isEqualTo("b22856f4cf5f1616");
        assertThat(json.get("logging.googleapis.com/trace_sampled").asBoolean()).isTrue();
        assertThat(json.get("logging.googleapis.com/labels").get("requestId").asText()).isEqualTo("req-1");
        assertThat(json.get("logging.googleapis.com/labels").get("operation").asText()).isEqualTo("GET /home");
    }

    @Test
    void format_omitsLabels_whenMdcHasNoCorrelationFields() {
        assertThat(formatter.format(event(Level.INFO, "startup", Map.of())))
                .doesNotContain("logging.googleapis.com/labels");
    }

    @Test
    void format_producesValidJson() throws Exception {
        String line = formatter.format(event(Level.INFO, "hello", Map.of()));
        new ObjectMapper().readTree(line);
    }

    @Test
    void format_promotesKeyValuePairsToTopLevelFields() throws Exception {
        // Top-level (not nested under labels) is what makes them queryable as jsonPayload.blockId.
        String line = formatter.format(event(Level.DEBUG, "Block dropped from the page", Map.of(),
                new KeyValuePair(LogFields.EVENT, LogEvents.BLOCK_DROPPED.name()),
                new KeyValuePair(LogFields.BLOCK_ID, "blt123")));

        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.get("event").asText()).isEqualTo("BLOCK_DROPPED");
        assertThat(json.get("blockId").asText()).isEqualTo("blt123");
    }

    @Test
    void format_keepsNumbersAndBooleansAsNativeJsonTypes() throws Exception {
        // Numeric range queries (jsonPayload.elapsedMs > 2000) only work if it is not a string.
        String line = formatter.format(event(Level.WARN, "Upstream call failed", Map.of(),
                new KeyValuePair(LogFields.ELAPSED_MS, 3021L),
                new KeyValuePair(LogFields.RETRYABLE, true)));

        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.get("elapsedMs").isNumber()).isTrue();
        assertThat(json.get("elapsedMs").asLong()).isEqualTo(3021L);
        assertThat(json.get("retryable").isBoolean()).isTrue();
    }

    @Test
    void format_rendersEnumValuesByName() throws Exception {
        String line = formatter.format(event(Level.DEBUG, "Block dropped", Map.of(),
                new KeyValuePair(LogFields.REASON, Level.WARN)));

        assertThat(new ObjectMapper().readTree(line).get("reason").asText()).isEqualTo("WARN");
    }

    @Test
    void format_ignoresReservedKeys() throws Exception {
        // A pair named "service" (or any field this formatter owns) must not overwrite the field
        // Cloud Logging classifies on — the same collision the MDC "service" key causes.
        String line = formatter.format(event(Level.ERROR, "boom", Map.of(),
                new KeyValuePair("service", "impostor"),
                new KeyValuePair("severity", "INFO"),
                new KeyValuePair("logging.googleapis.com/spanId", "forged")));

        JsonNode json = new ObjectMapper().readTree(line);
        assertThat(json.get("service").asText()).isEqualTo("ms-clp-blp");
        assertThat(json.get("severity").asText()).isEqualTo("ERROR");
        assertThat(json.has("logging.googleapis.com/spanId")).isFalse();
    }

    @Test
    void format_skipsNullValuedPairs() throws Exception {
        String line = formatter.format(event(Level.INFO, "hello", Map.of(),
                new KeyValuePair(LogFields.BLOCK_ID, null)));

        assertThat(new ObjectMapper().readTree(line).has("blockId")).isFalse();
    }

    private static ILoggingEvent event(Level level, String message, Map<String, String> mdc,
                                       KeyValuePair... keyValuePairs) {
        ILoggingEvent event = mock(ILoggingEvent.class);
        when(event.getLevel()).thenReturn(level);
        when(event.getFormattedMessage()).thenReturn(message);
        when(event.getTimeStamp()).thenReturn(System.currentTimeMillis());
        when(event.getLoggerName()).thenReturn("com.liverpool.ms_clp_blp.Test");
        when(event.getThreadName()).thenReturn("test-thread");
        when(event.getMDCPropertyMap()).thenReturn(mdc);
        when(event.getThrowableProxy()).thenReturn(null);
        when(event.getKeyValuePairs()).thenReturn(
                keyValuePairs.length == 0 ? null : List.of(keyValuePairs));
        return event;
    }
}
