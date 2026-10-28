package com.liverpool.ms_clp_blp.clients.feign;

import java.util.regex.Pattern;

/**
 * Redacts credentials from outbound trace lines.
 *
 * <p>The verbose Feign trace exists to reconstruct a failing call exactly, which is also why it used
 * to print {@code Authorization}, the Vertex Basic credentials, the wishlist/orders {@code apikey}
 * and the {@code SessionId} cookie verbatim into the log pipeline. Everything needed to diagnose a
 * call survives redaction: the header is still shown to be present, only its value is replaced.</p>
 *
 * <p>Matching is by header name, case-insensitively, and covers both shapes the trace produces —
 * {@code authorization: Basic …} from Feign's own logger and {@code Header 'authorization': […]}
 * from the request interceptor — plus the session id when it travels as a query parameter.</p>
 */
final class SensitiveHeaderMasker {

	static final String REDACTED = "***";

	/** Header names whose values never belong in a log line. */
	private static final String SENSITIVE_NAMES =
			"authorization|proxy-authorization|apikey|api-key|x-api-key|cookie|set-cookie|sessionid"
					+ "|x-sf-user-id|x-jml-user-id|x-jml-device-id";

	/** {@code authorization: Basic dXNlcg==} and {@code Header 'authorization': [Basic dXNlcg==]}. */
	private static final Pattern HEADER_LINE = Pattern.compile(
			"(?i)('?(?:" + SENSITIVE_NAMES + ")'?)(\\s*:\\s*)(\\[?)([^\\]\\r\\n]*)(\\]?)");

	/** {@code ?SessionId=abc&…} — the auth exchange carries it in the query string. */
	private static final Pattern QUERY_PARAM = Pattern.compile(
			"(?i)([?&](?:sessionid|apikey|api-key|token)=)([^&\\s]*)");

	private SensitiveHeaderMasker() {
	}

	/**
	 * Redacts every credential in one already-formatted trace line.
	 *
	 * @param line the line about to be logged (may be {@code null})
	 * @return the line with sensitive values replaced by {@value #REDACTED}
	 */
	static String mask(String line) {
		if (line == null || line.isEmpty()) {
			return line;
		}
		String masked = HEADER_LINE.matcher(line).replaceAll("$1$2$3" + REDACTED + "$5");
		return QUERY_PARAM.matcher(masked).replaceAll("$1" + REDACTED);
	}

	/**
	 * Redacts a header value by its name, for callers that still have the pair separated.
	 *
	 * @param name  the header name
	 * @param value the header value
	 * @return the value, or {@value #REDACTED} when the name is sensitive
	 */
	static Object maskValue(String name, Object value) {
		return name != null && name.toLowerCase().matches(SENSITIVE_NAMES) ? REDACTED : value;
	}
}
