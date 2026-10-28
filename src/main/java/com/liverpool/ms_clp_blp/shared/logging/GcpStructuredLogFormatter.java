package com.liverpool.ms_clp_blp.shared.logging;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;

/**
 * Emits log lines using Google Cloud Logging's recognized top-level JSON fields ({@code severity},
 * {@code message}, {@code time}, {@code logging.googleapis.com/trace}, {@code .../spanId},
 * {@code .../labels}) instead of ECS's nested {@code log.level}.
 *
 * <p>Cloud Run's log ingestion only classifies a structured (JSON) stdout/stderr line by severity
 * when it finds a top-level {@code severity} key it recognizes; ECS nests the level under
 * {@code log.level} instead, so every line silently lands as GCP severity {@code DEFAULT} — which is
 * exactly what this project's cost-optimization log exclusion filter drops before it's ever stored.
 * Registered via {@code logging.structured.format.console} in {@code application.yaml} (Spring Boot's
 * {@link StructuredLogFormatter} extension point, the same one backing the built-in {@code ecs}/
 * {@code gelf}/{@code logstash} formats) — reuses the existing MDC context
 * ({@code requestId}/{@code correlationId}/{@code operation} from {@code MdcRequestContextFilter},
 * {@code trace_id}/{@code span_id}/{@code trace_flags} from the OpenTelemetry Java agent) with no new
 * context-propagation code.</p>
 *
 * <p>Key-value pairs attached through SLF4J's fluent API ({@code log.atWarn().addKeyValue(...)}) are
 * promoted to <em>top-level</em> JSON fields, which is what makes them queryable as
 * {@code jsonPayload.blockId} in Cloud Logging. Unlike MDC, those pairs are scoped to the single log
 * line rather than to the thread, so blocks resolved concurrently cannot leak context into each
 * other and nothing needs clearing in a {@code finally}. See {@link LogFields} for the key
 * catalogue.</p>
 */
public class GcpStructuredLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final String SERVICE_NAME = "ms-clp-blp";
    private static final String PROJECT_ID = System.getenv("GCP_PROJECT_ID");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Keys this formatter owns. A key-value pair reusing one of them would overwrite the field Cloud
     * Logging classifies on — the same collision the MDC {@code service} key causes — so they are
     * dropped rather than allowed to win.
     */
    private static final Set<String> RESERVED_KEYS =
            Set.of("severity", "message", "time", "logger", "service", "thread", "stack_trace");

    private static final String GCP_FIELD_PREFIX = "logging.googleapis.com/";

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("severity", toGcpSeverity(event.getLevel().toString()));
        fields.put("message", event.getFormattedMessage());
        fields.put("time", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.getTimeStamp())));
        fields.put("logger", event.getLoggerName());
        fields.put("service", SERVICE_NAME);
        fields.put("thread", event.getThreadName());

        Map<String, String> mdc = event.getMDCPropertyMap();
        addTraceAndSpan(fields, mdc);
        addLabels(fields, mdc);
        addKeyValues(fields, event);

        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            fields.put("stack_trace", ThrowableProxyUtil.asString(throwable));
        }

        return writeJson(fields);
    }

    /**
     * Joins the log line to its distributed trace.
     *
     * <p>The OpenTelemetry Java agent's logback-mdc instrumentation injects {@code trace_id},
     * {@code span_id} and {@code trace_flags} into the map returned by
     * {@link ILoggingEvent#getMDCPropertyMap()} — the ids of the span actually exported to Dynatrace,
     * which is what makes a Cloud Logging entry and a Dynatrace trace the same trace. The camelCase
     * {@code traceId}/{@code spanId} keys are the fallback: they are what a Micrometer Tracing bridge
     * would publish, and reading both means this formatter stays correct whichever produces them.</p>
     */
    private void addTraceAndSpan(Map<String, Object> fields, Map<String, String> mdc) {
        String traceId = firstPresent(mdc, "trace_id", "traceId");
        if (hasText(traceId) && hasText(PROJECT_ID)) {
            fields.put("logging.googleapis.com/trace", "projects/" + PROJECT_ID + "/traces/" + traceId);
        }
        String spanId = firstPresent(mdc, "span_id", "spanId");
        if (hasText(spanId)) {
            fields.put("logging.googleapis.com/spanId", spanId);
        }
        // trace_flags is 2 hex digits whose low bit is the W3C "sampled" flag. Cloud Logging uses
        // trace_sampled to decide whether to offer the trace link on the entry, so an unsampled line
        // does not advertise a trace nobody exported.
        String traceFlags = mdc.get("trace_flags");
        if (hasText(traceFlags)) {
            fields.put("logging.googleapis.com/trace_sampled", isSampled(traceFlags));
        }
    }

    /** Parses W3C {@code trace-flags}: sampled is bit 0 of the hex byte, e.g. {@code 01}. */
    private static boolean isSampled(String traceFlags) {
        try {
            return (Integer.parseInt(traceFlags.trim(), 16) & 1) == 1;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private void addLabels(Map<String, Object> fields, Map<String, String> mdc) {
        Map<String, String> labels = new LinkedHashMap<>();
        putIfPresent(labels, mdc, "correlationId");
        putIfPresent(labels, mdc, "requestId");
        putIfPresent(labels, mdc, "operation");
        if (!labels.isEmpty()) {
            fields.put("logging.googleapis.com/labels", labels);
        }
    }

    /**
     * Promotes the event's fluent-API key-value pairs to top-level fields so each one becomes an
     * independently queryable {@code jsonPayload.<key>} in Cloud Logging.
     */
    private void addKeyValues(Map<String, Object> fields, ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs == null || pairs.isEmpty()) {
            return;
        }
        for (KeyValuePair pair : pairs) {
            if (pair == null || !hasText(pair.key) || pair.value == null || isReserved(pair.key)) {
                continue;
            }
            fields.put(pair.key, normalize(pair.value));
        }
    }

    private static boolean isReserved(String key) {
        return RESERVED_KEYS.contains(key) || key.startsWith(GCP_FIELD_PREFIX);
    }

    /**
     * Keeps the values Jackson maps to native JSON types (so numeric comparisons like
     * {@code jsonPayload.elapsedMs > 2000} work) and stringifies anything else, so an unexpected
     * value can never fail serialization and cost the whole log line.
     */
    private static Object normalize(Object value) {
        return switch (value) {
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Enum<?> e -> e.name();
            case Map<?, ?> m -> m;
            case Collection<?> c -> c;
            default -> String.valueOf(value);
        };
    }

    private String writeJson(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields) + "\n";
        } catch (Exception ex) {
            return "{\"severity\":\"ERROR\",\"message\":\"failed to serialize log entry: "
                    + ex.getClass().getSimpleName() + "\"}\n";
        }
    }

    /**
     * Maps Logback's levels to GCP's {@code LogSeverity} enum values — notably {@code WARN} to
     * {@code WARNING} and both {@code TRACE}/{@code DEBUG} to {@code DEBUG} (GCP has no TRACE).
     */
    private static String toGcpSeverity(String logbackLevel) {
        return switch (logbackLevel) {
            case "TRACE", "DEBUG" -> "DEBUG";
            case "INFO" -> "INFO";
            case "WARN" -> "WARNING";
            case "ERROR" -> "ERROR";
            default -> "DEFAULT";
        };
    }

    /** Returns the value of the first key that carries text, or {@code null} if none does. */
    private static String firstPresent(Map<String, String> mdc, String... keys) {
        for (String key : keys) {
            String value = mdc.get(key);
            if (hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, String> target, Map<String, String> mdc, String key) {
        String value = mdc.get(key);
        if (hasText(value)) {
            target.put(key, value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
