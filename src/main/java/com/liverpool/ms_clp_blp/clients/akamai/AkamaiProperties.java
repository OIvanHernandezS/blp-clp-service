package com.liverpool.ms_clp_blp.clients.akamai;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings and EdgeGrid credentials for Akamai's Fast Purge v3 API.
 *
 * <p><strong>There is no username/password mode.</strong> Akamai authenticates API clients with
 * EdgeGrid ({@code EG1-HMAC-SHA256}), whose entire credential is the four values below; a basic-auth
 * pair would be rejected outright. Three of them are secrets and are injected by environment variable
 * with no committed default (Rule 10) — see {@code AKAMAI_*} in {@code .env.example}.</p>
 *
 * <p>{@link #host} feeds both the signature and {@code akamai.base-url}, which is why the YAML
 * derives the URL from the same {@code AKAMAI_HOST} variable rather than declaring a second one: the
 * signed host and the host actually dialled must agree or the edge answers 403.</p>
 *
 * <p>Never log an instance of this record. A record's generated {@code toString()} prints every
 * component, so passing one to a log line would print all three secrets; {@code AkamaiPurgeLoggingTest}
 * pins that no purge log line ever carries them.</p>
 *
 * @param host           API hostname issued with the credential, e.g.
 *                       {@code akab-xxxxx.luna.akamaiapis.net}; blank until configured, which
 *                       {@code CachePurgeUseCases} rejects before any call leaves the process
 * @param baseUrl        {@code https://} + {@link #host}, resolved eagerly by Feign at startup
 * @param clientToken    EdgeGrid {@code client_token} (secret — env var only)
 * @param clientSecret   EdgeGrid {@code client_secret}, the HMAC signing key (secret — env var only)
 * @param accessToken    EdgeGrid {@code access_token} (secret — env var only)
 * @param connectTimeout TCP connect timeout for purge calls
 * @param readTimeout    read timeout; a purge is accepted asynchronously, so this bounds the
 *                       acknowledgement rather than the purge itself
 */
@ConfigurationProperties(prefix = "akamai")
public record AkamaiProperties(
		String host,
		String baseUrl,
		String clientToken,
		String clientSecret,
		String accessToken,
		Duration connectTimeout,
		Duration readTimeout) {

	/**
	 * Whether every value the EdgeGrid signature needs is present.
	 *
	 * <p>Checked before a purge is attempted so an unconfigured environment fails with a
	 * configuration error naming the missing variable, instead of a 403 from the edge that looks
	 * identical to a revoked credential.</p>
	 *
	 * @return {@code true} when host and all three EdgeGrid tokens are non-blank
	 */
	public boolean configured() {
		return isPresent(host) && isPresent(clientToken) && isPresent(clientSecret) && isPresent(accessToken);
	}

	private static boolean isPresent(String value) {
		return value != null && !value.isBlank();
	}
}
