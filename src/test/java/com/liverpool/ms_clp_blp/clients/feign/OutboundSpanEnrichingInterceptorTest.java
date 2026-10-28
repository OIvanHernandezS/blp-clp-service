package com.liverpool.ms_clp_blp.clients.feign;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins that the span the javaagent's OkHttp instrumentation creates for a call ends up carrying the
 * same query/header/body detail as {@link FeignOutboundLogger}'s curl line — masked and truncated the
 * same way, gated by the same {@code logging.outbound.feign-enabled} flag — and that a fault while
 * enriching never breaks or blocks the real call.
 */
class OutboundSpanEnrichingInterceptorTest {

    private static final AttributeKey<String> QUERY = AttributeKey.stringKey("upstream.request.query");
    private static final AttributeKey<String> HEADERS = AttributeKey.stringKey("upstream.request.headers");
    private static final AttributeKey<String> BODY = AttributeKey.stringKey("upstream.request.body");

    private InMemorySpanExporter exporter;
    private SdkTracerProvider tracerProvider;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void attachesTheMaskedAndTruncatedQuery() throws IOException {
        SpanData span = runWithSpan(interceptor(false),
                request("https://auth.liverpool.com.mx/v2/auth/exchange-token?SessionId=abc123", null));

        assertThat(span.getAttributes().get(QUERY)).isEqualTo("?SessionId=***");
    }

    @Test
    void attachesHeadersWithSensitiveValuesMasked() throws IOException {
        Request request = new Request.Builder()
                .url("https://auth.liverpool.com.mx/v2/auth/exchange-token")
                .header("tenant", "web")
                .header("authorization", "Bearer super-secret")
                .build();

        SpanData span = runWithSpan(interceptor(false), request);

        String headers = span.getAttributes().get(HEADERS);
        assertThat(headers)
                .contains("tenant: web")
                .contains("authorization: ***")
                .doesNotContain("super-secret");
    }

    @Test
    void attachesTheBodyOnlyWhenFeignLoggingIsEnabled() throws IOException {
        Request request = request("https://vertex.liverpool.com.mx/search",
                RequestBody.create("{\"query\":\"tv\"}", MediaType.get("application/json")));

        SpanData disabled = runWithSpan(interceptor(false), request);
        assertThat(disabled.getAttributes().get(BODY)).isNull();

        SpanData enabled = runWithSpan(interceptor(true), request);
        assertThat(enabled.getAttributes().get(BODY)).isEqualTo("{\"query\":\"tv\"}");
    }

    @Test
    void neverMasksTheBodySincePlainLoggingAlreadyDoesNot() throws IOException {
        Request request = request("https://auth.liverpool.com.mx/v2/auth/exchange-token",
                RequestBody.create("{\"apikey\":\"do-not-mask-bodies\"}", MediaType.get("application/json")));

        SpanData span = runWithSpan(interceptor(true), request);

        assertThat(span.getAttributes().get(BODY)).contains("do-not-mask-bodies");
    }

    @Test
    void truncatesAnOversizedBody() throws IOException {
        String oversized = "x".repeat(600);
        Request request = request("https://vertex.liverpool.com.mx/search",
                RequestBody.create(oversized, MediaType.get("application/json")));

        SpanData span = runWithSpan(interceptor(true), request);

        assertThat(span.getAttributes().get(BODY))
                .hasSizeLessThan(oversized.length())
                .endsWith("…(truncated)");
    }

    @Test
    void aFaultWhileReadingTheBodyNeverBreaksOrBlocksTheCall() throws IOException {
        RequestBody explodingBody = new RequestBody() {
            @Override
            public MediaType contentType() {
                return MediaType.get("application/json");
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                throw new IOException("boom");
            }
        };
        Request request = new Request.Builder()
                .url("https://auth.liverpool.com.mx/v2/auth/exchange-token?SessionId=abc123")
                .header("tenant", "web")
                .post(explodingBody)
                .build();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(dummyResponse(request));

        Span span = tracerProvider.get("test").spanBuilder("op").startSpan();
        try (Scope scope = span.makeCurrent()) {
            assertThat(interceptor(true).intercept(chain)).isNotNull();
        } finally {
            span.end();
        }

        verify(chain, times(1)).proceed(any());
        SpanData spanData = exporter.getFinishedSpanItems().getFirst();
        // Query/headers, set before the body blew up, still made it onto the span.
        assertThat(spanData.getAttributes().get(QUERY)).isEqualTo("?SessionId=***");
        assertThat(spanData.getAttributes().get(BODY)).isNull();
    }

    @Test
    void doesNothingAndNeverThrowsWhenNoSpanIsActive() throws IOException {
        Request request = request("https://vertex.liverpool.com.mx/search", null);
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(dummyResponse(request));

        assertThat(interceptor(false).intercept(chain)).isNotNull();

        verify(chain, times(1)).proceed(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static OutboundSpanEnrichingInterceptor interceptor(boolean feignEnabled) {
        return new OutboundSpanEnrichingInterceptor(new OutboundLoggingProperties(feignEnabled, List.of()));
    }

    private static Request request(String url, RequestBody body) {
        Request.Builder builder = new Request.Builder().url(HttpUrl.get(url));
        return body == null ? builder.build() : builder.post(body).build();
    }

    private SpanData runWithSpan(OutboundSpanEnrichingInterceptor interceptor, Request request) throws IOException {
        exporter.reset();
        Interceptor.Chain chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.proceed(any())).thenReturn(dummyResponse(request));

        Span span = tracerProvider.get("test").spanBuilder("op").startSpan();
        try (Scope scope = span.makeCurrent()) {
            interceptor.intercept(chain);
        } finally {
            span.end();
        }
        return exporter.getFinishedSpanItems().getFirst();
    }

    private static Response dummyResponse(Request request) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build();
    }
}
