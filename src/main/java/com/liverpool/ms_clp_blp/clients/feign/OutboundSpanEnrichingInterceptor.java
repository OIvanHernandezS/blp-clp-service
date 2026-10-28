package com.liverpool.ms_clp_blp.clients.feign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;

import io.opentelemetry.api.trace.Span;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

/**
 * Attaches the outbound request's query, headers and (gated) body to the span the OTel javaagent's
 * OkHttp instrumentation already created for this call.
 *
 * <h3>Why a network interceptor, specifically</h3>
 * <p>Verified empirically against the real agent (2.31.1): the CLIENT span is not current yet when an
 * <em>application</em> interceptor ({@code addInterceptor}) runs — {@link Span#current()} there still
 * resolves to whatever span was active before the call (the inbound request span, typically), so
 * anything set from an application interceptor lands on the wrong span. By the time a <em>network</em>
 * interceptor ({@code addNetworkInterceptor}) runs, OkHttp has already handed off into the segment the
 * instrumentation wraps, and {@code Span.current()} correctly resolves to the real CLIENT span. This
 * is also why {@code FeignOutboundLogger}'s hooks can't do this job: {@code logRequest} fires before
 * {@code Client.execute()} even starts, and {@code logAndRebufferResponse} fires after it returns —
 * both outside the span entirely, application interceptor or not.</p>
 *
 * <h3>Known limitation: pure connection failures</h3>
 * <p>A network interceptor only runs once a connection attempt reaches that stage. When the call never
 * gets that far — connection refused, DNS failure, connect timeout — OkHttp never invokes the network
 * interceptor chain at all, yet the agent still emits a CLIENT span for the attempt (with
 * {@code error.type} set to the exception). There is no OkHttp interceptor hook, application or
 * network, positioned early enough to enrich that specific span; only calls that at least reach the
 * wire (any HTTP response, success or error status) get the {@code upstream.request.*} attributes
 * this class sets. {@code FeignOutboundLogger}'s WARN line remains the only place a pure connection
 * failure is diagnosed today.</p>
 *
 * <p>Mirrors {@code FeignOutboundLogger}'s curl line on purpose: query and headers are masked by
 * {@link SensitiveHeaderMasker} and always attached; the body is never masked and is attached only
 * when {@link OutboundLoggingProperties#feignEnabled()} is on — the same PII gate the logs already
 * use, so a span is never more revealing than what the team already allows into logs. Every value is
 * trimmed by {@link OutboundTextTruncator} before it is set, so one oversized payload cannot inflate
 * span export cost.</p>
 *
 * <p>Registered per {@code <System>FeignConfig}, one instance per isolated {@code OkHttpClient} (Rule
 * 4), never shared globally.</p>
 */
public final class OutboundSpanEnrichingInterceptor implements Interceptor {

	private static final Logger log = LoggerFactory.getLogger(OutboundSpanEnrichingInterceptor.class);

	private static final String ATTR_QUERY = "upstream.request.query";
	private static final String ATTR_HEADERS = "upstream.request.headers";
	private static final String ATTR_BODY = "upstream.request.body";

	private final OutboundLoggingProperties properties;

	/**
	 * @param properties outbound logging settings; the same gate {@code FeignOutboundLogger} uses to
	 *                   decide whether a request body may leave the process at all
	 */
	public OutboundSpanEnrichingInterceptor(OutboundLoggingProperties properties) {
		this.properties = properties;
	}

	/**
	 * Enriches the current span with the outgoing request's data, then always forwards the call
	 * unchanged — enrichment failures never touch the request or the response.
	 *
	 * @param chain OkHttp's interceptor chain
	 * @return the response from the rest of the chain, untouched
	 * @throws IOException propagated from {@code chain.proceed}, never from enrichment
	 */
	@Override
	public Response intercept(Chain chain) throws IOException {
		Request request = chain.request();
		enrich(request);
		return chain.proceed(request);
	}

	private void enrich(Request request) {
		try {
			Span span = Span.current();
			if (!span.isRecording()) {
				return;
			}
			String query = request.url().encodedQuery();
			if (query != null && !query.isBlank()) {
				String masked = SensitiveHeaderMasker.mask("?" + query);
				span.setAttribute(ATTR_QUERY, OutboundTextTruncator.truncate(masked));
			}
			String headers = renderHeaders(request.headers());
			if (headers != null) {
				span.setAttribute(ATTR_HEADERS, OutboundTextTruncator.truncate(headers));
			}
			if (properties.feignEnabled()) {
				String body = readBody(request.body());
				if (body != null) {
					span.setAttribute(ATTR_BODY, OutboundTextTruncator.truncate(body));
				}
			}
		} catch (Exception e) {
			log.debug("Skipped span enrichment for {} {}", request.method(), request.url(), e);
		}
	}

	private static String renderHeaders(Headers headers) {
		if (headers.size() == 0) {
			return null;
		}
		StringBuilder rendered = new StringBuilder();
		for (int i = 0; i < headers.size(); i++) {
			String name = headers.name(i);
			if (!rendered.isEmpty()) {
				rendered.append(", ");
			}
			rendered.append(name).append(": ").append(SensitiveHeaderMasker.maskValue(name, headers.value(i)));
		}
		return rendered.toString();
	}

	private static String readBody(RequestBody body) throws IOException {
		if (body == null) {
			return null;
		}
		Buffer buffer = new Buffer();
		body.writeTo(buffer);
		return buffer.readString(StandardCharsets.UTF_8);
	}
}
