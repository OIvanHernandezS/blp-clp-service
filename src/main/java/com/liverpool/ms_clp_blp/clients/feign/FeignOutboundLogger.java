package com.liverpool.ms_clp_blp.clients.feign;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.liverpool.ms_clp_blp.clients.OutboundLoggingProperties;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import feign.Request;
import feign.Response;
import feign.Util;

/**
 * Reports every outbound call in at most two lines.
 *
 * <h3>What lands at each level</h3>
 * <ul>
 *   <li><strong>INFO: nothing.</strong> A healthy call is not news, and the previous behaviour —
 *       Feign's own {@code Level.FULL} trace at INFO — emitted around thirty lines per call, one per
 *       request header, one per response header. A single page request buried the log in
 *       transport chatter and drowned out the lines that say what the page actually did.</li>
 *   <li><strong>WARN: one line per failed call</strong> — the upstream, the status, how long it took,
 *       and the upstream's own error body, which is usually the thing that names the cause
 *       ({@code {"code":"TOKEN_NOT_FOUND"}} explains a 404 that {@code httpStatus=404} does not).</li>
 *   <li><strong>DEBUG: one {@code curl} line per request</strong> plus one line per response. The
 *       request is rendered as a runnable command rather than as Feign's header-per-line dump,
 *       because the point of reading it during an incident is to replay it.</li>
 * </ul>
 *
 * <p>{@code FEIGN_LOG_ENABLED} no longer controls visibility — the log level does. It now controls
 * <em>bodies</em>: with it on, the {@code curl} carries the request body and successful responses
 * carry theirs. Failed responses always carry their body, truncated, because that is the diagnosis.</p>
 *
 * <h3>Why not the stock {@code feign.slf4j.Slf4jLogger}</h3>
 * <p>It guards its hooks with {@code isDebugEnabled()} against a logger named after the client
 * interface, so the trace depends on a per-interface category nobody configures. This class overrides
 * every hook Feign calls, so the base class's textual output is never produced at all.</p>
 *
 * <p>Reading a response body obliges us to hand the decoder a fresh copy — that is what
 * {@code toBuilder().body(...)} below does, and it is why response bodies must never be read from a
 * {@code RequestInterceptor} instead.</p>
 *
 * <h3>Not every non-2xx is a failure</h3>
 * <p>Some upstreams answer a perfectly normal question with a 4xx: cart-service says "this user has
 * no cart" with a 404 (ADR-059). Those are business answers, and the adapter that asked already
 * reports them. Reporting them here too would log the same case twice at WARN; {@link OutboundLoggingProperties#isExpected} is the list that stops that.</p>
 */
public class FeignOutboundLogger extends feign.Logger {

	private static final Logger log = LoggerFactory.getLogger(FeignOutboundLogger.class);

	private static final int HTTP_OK = 200;
	private static final int HTTP_MULTIPLE_CHOICES = 300;
	/** Reported when the call never received a response (connect/read timeout, DNS, reset). */
	private static final int NO_RESPONSE = 0;

	private final OutboundLoggingProperties properties;

	/**
	 * @param properties outbound logging settings, including the {@code provider:status} pairs whose
	 *                   outcome is a normal business answer; read per call so the body detail responds
	 *                   to the current configuration rather than to bean-creation time
	 */
	public FeignOutboundLogger(OutboundLoggingProperties properties) {
		this.properties = properties;
	}

	/**
	 * Renders the outgoing request as a single {@code curl} command.
	 *
	 * @param configKey Feign's {@code Interface#method(..)} key for the call
	 * @param logLevel  Feign's configured verbosity, unused — this class decides its own detail
	 * @param request   the request about to go on the wire
	 */
	@Override
	protected void logRequest(String configKey, Level logLevel, Request request) {
		if (!log.isDebugEnabled()) {
			return;
		}
		LogEvents.at(log, LogEvents.UPSTREAM_CALL_STARTED)
				.addKeyValue(LogFields.UPSTREAM, upstreamOf(configKey))
				.addKeyValue(LogFields.UPSTREAM_OPERATION, configKey)
				.log(toCurl(request));
	}

	/**
	 * Reports the call's outcome, re-buffering the body whenever it was read.
	 *
	 * @param configKey   Feign's {@code Interface#method(..)} key for the call
	 * @param logLevel    Feign's configured verbosity, unused — this class decides its own detail
	 * @param response    the upstream response
	 * @param elapsedTime milliseconds the call took
	 * @return the response, re-buffered when its body was consumed for logging
	 * @throws IOException if the body cannot be read
	 */
	@Override
	protected Response logAndRebufferResponse(String configKey, Level logLevel, Response response,
			long elapsedTime) throws IOException {
		boolean succeeded = isSuccess(response.status());
		// A non-2xx that the domain already treats as a normal answer, e.g. cart's "you have no cart".
		// It is not a failure of anything, and the adapter that understands it already reports it —
		// so this class says nothing at all about it.
		boolean expected = !succeeded
				&& properties.isExpected(upstreamOf(configKey), response.status());
		// A failure's body is the diagnosis, so it is always read; a success's only when asked for.
		// An expected outcome follows the success path: its body is the ordinary response, and reading
		// it would allocate a copy on every guest with an empty cart for nothing.
		boolean readBody = (!succeeded && !expected)
				|| (properties.feignEnabled() && log.isDebugEnabled());

		Response rebuffered = response;
		String body = null;
		if (readBody && response.body() != null) {
			byte[] data = Util.toByteArray(response.body().asInputStream());
			rebuffered = response.toBuilder().body(data).build();
			body = truncate(new String(data, StandardCharsets.UTF_8));
		}

		if (!expected) {
			LogEvents.at(log, succeeded ? LogEvents.UPSTREAM_CALL_OK : LogEvents.UPSTREAM_CALL_FAILED)
					.addKeyValue(LogFields.UPSTREAM, upstreamOf(configKey))
					.addKeyValue(LogFields.UPSTREAM_OPERATION, configKey)
					.addKeyValue(LogFields.HTTP_STATUS, response.status())
					.addKeyValue(LogFields.ELAPSED_MS, elapsedTime)
					.addKeyValue(LogFields.RESPONSE_BODY, body)
					.log(succeeded ? "Outbound call completed" : "Outbound call returned an error status");
		}
		return rebuffered;
	}

	/**
	 * Reports a transport-level failure — the case with no status and no body to report at all.
	 *
	 * @param configKey   Feign's {@code Interface#method(..)} key for the call
	 * @param logLevel    Feign's configured verbosity, unused
	 * @param ioe         the transport failure
	 * @param elapsedTime milliseconds spent before it failed
	 * @return the exception, unchanged, for Feign to propagate
	 */
	@Override
	protected IOException logIOException(String configKey, Level logLevel, IOException ioe, long elapsedTime) {
		LogEvents.at(log, LogEvents.UPSTREAM_CALL_FAILED)
				.addKeyValue(LogFields.UPSTREAM, upstreamOf(configKey))
				.addKeyValue(LogFields.UPSTREAM_OPERATION, configKey)
				.addKeyValue(LogFields.HTTP_STATUS, NO_RESPONSE)
				.addKeyValue(LogFields.ELAPSED_MS, elapsedTime)
				.addKeyValue(LogFields.ERROR_CLASS, ioe.getClass().getSimpleName())
				.addKeyValue(LogFields.DETAIL, ioe.getMessage())
				.log("Outbound call never received a response");
		return ioe;
	}

	/**
	 * Never invoked: every hook Feign calls is overridden above, so the base class never asks this
	 * one to render anything. Kept because {@code feign.Logger} declares it abstract.
	 *
	 * @param configKey Feign's {@code Interface#method(..)} key for the call
	 * @param format    printf-style template supplied by the base class
	 * @param args      template arguments
	 */
	@Override
	protected void log(String configKey, String format, Object... args) {
		// Intentionally silent — see the class javadoc.
	}

	// ── private ──────────────────────────────────────────────────────────────────────────────────

	/**
	 * Renders a request as a runnable {@code curl}, with credentials redacted. The body is included
	 * only when {@code FEIGN_LOG_ENABLED} is on, so an ordinary DEBUG session stays one short line
	 * per call.
	 */
	private String toCurl(Request request) {
		StringBuilder curl = new StringBuilder("curl -i -X ")
				.append(request.httpMethod().name())
				.append(" '").append(SensitiveHeaderMasker.mask(request.url())).append('\'');

		for (Map.Entry<String, Collection<String>> header : request.headers().entrySet()) {
			for (String value : header.getValue()) {
				curl.append(" -H '").append(header.getKey()).append(": ")
						.append(SensitiveHeaderMasker.maskValue(header.getKey(), value)).append('\'');
			}
		}

		if (properties.feignEnabled() && request.body() != null && request.body().length > 0) {
			curl.append(" --data '")
					.append(truncate(new String(request.body(), StandardCharsets.UTF_8)))
					.append('\'');
		}
		return curl.toString();
	}

	private static boolean isSuccess(int status) {
		return status >= HTTP_OK && status < HTTP_MULTIPLE_CHOICES;
	}

	private static String truncate(String value) {
		return OutboundTextTruncator.truncate(value);
	}

	/**
	 * Derives a stable upstream name from Feign's config key so calls can be grouped by the system
	 * they hit rather than by the interface method that happened to make them:
	 * {@code SalesforceCarrouselsClient#getCarrousels(..)} becomes {@code salesforcecarrousels}.
	 */
	private static String upstreamOf(String configKey) {
		if (configKey == null || configKey.isBlank()) {
			return null;
		}
		int methodSeparator = configKey.indexOf('#');
		String type = methodSeparator > 0 ? configKey.substring(0, methodSeparator) : configKey;
		return type.replace("Client", "").toLowerCase(Locale.ROOT);
	}
}
