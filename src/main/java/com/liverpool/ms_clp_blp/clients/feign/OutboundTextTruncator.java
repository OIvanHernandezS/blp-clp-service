package com.liverpool.ms_clp_blp.clients.feign;

/**
 * Trims outbound request/response text (bodies, query strings, header dumps) to a size that is
 * enough to diagnose a call with, shared by {@link FeignOutboundLogger} and
 * {@link OutboundSpanEnrichingInterceptor} so the log line and the span attribute agree on exactly
 * how much of a payload survives.
 */
final class OutboundTextTruncator {

	/** Enough of a body to diagnose with; upstream error payloads are far shorter than this. */
	static final int MAX_CHARS = 500;

	private OutboundTextTruncator() {
	}

	/**
	 * Collapses whitespace and truncates to {@value #MAX_CHARS} characters.
	 *
	 * @param value the raw text (may be {@code null} or blank)
	 * @return the collapsed, truncated text, or {@code null} when {@code value} was blank
	 */
	static String truncate(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String collapsed = value.replaceAll("\\s+", " ").trim();
		return collapsed.length() <= MAX_CHARS
				? collapsed
				: collapsed.substring(0, MAX_CHARS) + "…(truncated)";
	}
}
