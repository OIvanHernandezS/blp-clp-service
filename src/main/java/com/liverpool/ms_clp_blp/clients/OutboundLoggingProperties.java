package com.liverpool.ms_clp_blp.clients;

import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for outbound-call logging.
 *
 * <p>{@code feignEnabled} adds <strong>bodies</strong> to the outbound trace: the request body on the
 * {@code curl} line and the response body on a successful call. It does <em>not</em> control whether
 * the trace is visible — the log level does, and {@code FeignOutboundLogger} describes exactly what
 * lands at each one. Credential-bearing headers and query parameters ({@code Authorization}, the
 * Vertex Basic credentials, the {@code SessionId} cookie and query value) are always redacted by
 * {@code SensitiveHeaderMasker}; <strong>bodies are not</strong>, so a payload carrying personal data
 * reaches the log pipeline while this is on.</p>
 *
 * <p>It defaults to {@code false} everywhere and is switched on automatically only under the local
 * {@code LOG_FORMAT=pretty} document in {@code application.yaml}. Turn it on for a targeted incident,
 * then turn it back off. With it off, a failing call still reports its upstream, status, elapsed time
 * and the upstream's own error body at WARN — the diagnosis needs nothing enabled.</p>
 *
 * @param feignEnabled     whether request and successful-response bodies are included in the trace
 * @param expectedFailures {@code provider:status} pairs whose outcome is a normal business answer
 *                         rather than a failure — see {@link #isExpected}
 */
@ConfigurationProperties(prefix = "logging.outbound")
public record OutboundLoggingProperties(boolean feignEnabled, List<String> expectedFailures) {

	/**
	 * Normalises the rules once, at construction, rather than on every failed call.
	 *
	 * <p>Blank and {@code null} elements are dropped — {@code List.copyOf} would throw on the latter,
	 * which would turn a stray comma in an environment variable into a service that does not start.
	 * Malformed-but-present entries are deliberately <em>kept</em>: they can never match anything (a
	 * rule is compared whole, so {@code "auth"} is not {@code "auth:404"}), and keeping them means a
	 * typo still shows up in the startup line where someone can see it, instead of vanishing.</p>
	 */
	public OutboundLoggingProperties {
		expectedFailures = expectedFailures == null ? List.of() : expectedFailures.stream()
				.filter(rule -> rule != null && !rule.isBlank())
				.map(rule -> rule.strip().toLowerCase(Locale.ROOT))
				.toList();
	}

	/**
	 * Whether this provider answering with this status is a normal business outcome rather than a
	 * failure. A match suppresses the {@code UPSTREAM_CALL_FAILED} WARN: the adapter that understands
	 * the answer already reports it (auth's 401/404 becomes {@code SESSION_DEGRADED_TO_GUEST}), so
	 * logging it here too would report the same case twice.
	 *
	 * <p>A pair, never a provider alone: {@code auth:500} is a real outage and must still be logged.
	 * A malformed entry is ignored, never read as "suppress everything".</p>
	 *
	 * @param provider the upstream name, as {@code FeignOutboundLogger.upstreamOf} derives it
	 * @param status   the HTTP status the upstream answered with
	 * @return {@code true} when this outcome must not be treated as a failure
	 */
	public boolean isExpected(String provider, int status) {
		if (provider == null || provider.isBlank() || expectedFailures.isEmpty()) {
			return false;
		}
		return expectedFailures.contains(provider.strip().toLowerCase(Locale.ROOT) + ':' + status);
	}
}
