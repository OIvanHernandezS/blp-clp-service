package com.liverpool.ms_clp_blp.clients.auth;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import com.liverpool.ms_clp_blp.clients.auth.dto.AuthExchangeResponse;

/**
 * Declarative client for the shared auth microservice's token-exchange endpoint.
 *
 * <h3>The session id must reach the wire percent-encoded</h3>
 * <p>A {@code SessionId} is a base64 signature and routinely contains {@code +}, {@code /} and
 * {@code =}. A raw {@code +} is the dangerous one: the auth service applies form-encoding semantics,
 * reads it as a space, and answers {@code 401 Invalid token signature}. The failure is silent from
 * the caller's side — {@code AuthTokenExchangeAdapter} degrades the user to guest rather than
 * erroring — so it surfaces not as an outage but as everyone appearing logged out.
 *
 * <p>Feign percent-encodes {@code @RequestParam} values, emitting {@code %2B} for {@code +} and
 * {@code %3D} for {@code =}. It leaves {@code /} raw, which differs from the superseded
 * {@code RestClient} call's strict per-component encoding but is correct: RFC 3986 defines the query
 * component as {@code *( pchar / "/" / "?" )}, so a slash is legal there and carries no alternate
 * meaning. {@code AuthClientWireTest} pins all of this against a real socket, including a round-trip
 * assertion that the server receives the value byte-for-byte — treat it as a contract, not a
 * formality.</p>
 */
@FeignClient(
		name = "authClient",
		url = "${auth.base-url}",
		configuration = AuthFeignConfig.class)
public interface AuthClient {

	/**
	 * Query-parameter name carrying the session id.
	 *
	 * <p>A literal, not {@code ${auth.session-cookie-name}}: Spring Cloud's {@code SpringMvcContract}
	 * resolves property placeholders in the mapping <em>path</em> but not in a
	 * {@code @RequestParam} name, so a placeholder here is sent verbatim as the parameter name and the
	 * auth service silently never sees a session id. {@code auth.session-cookie-name} stays configurable
	 * for its real job — naming the inbound cookie {@code SessionContextAdapter} reads.</p>
	 */
	String SESSION_ID_PARAM = "SessionId";

	/**
	 * Header carrying the brand discriminator.
	 *
	 * <p>A compile-time constant rather than a literal in the annotation because
	 * {@link AuthBrandHostInterceptor} reads the very same header back off the request template to
	 * decide which origin to send it to; two spellings that drift apart would silently disable the
	 * per-brand routing. Unlike {@link #SESSION_ID_PARAM} the concern here is duplication, not
	 * placeholder resolution — a {@code static final String} is inlined by the compiler and is a
	 * perfectly good annotation value.</p>
	 */
	String BRAND_HEADER = "x-brand-id";

	/**
	 * Header carrying the client platform ({@code web}, {@code android}, {@code ios}). The v3 auth
	 * contract picks the Auth0 client from it, and a refresh token is bound to the client that issued
	 * it — exchanging with the wrong one answers {@code invalid_grant}. Forwarded to the auth service
	 * only, never to any other upstream (same rule as digital_bff).
	 */
	String CLIENT_PLATFORM_HEADER = "x-client-platform";

	/**
	 * Exchanges a session id for the caller's login state and profile id, on the contract version the
	 * caller asked for.
	 *
	 * <p>The path comes from {@code auth.exchange-token-path-template}
	 * ({@code /v{version}/auth/exchange-token}): Spring resolves the property placeholder when the
	 * contract is parsed, and Feign fills {@code {version}} per call. Request and response shapes are the
	 * same on every version; on v3 the auth service picks the Auth0 client from
	 * {@code x-client-platform}. Which versions may be asked for is decided before this call
	 * ({@link AuthProperties#supportsVersion(int)}) — an unpublished one answers {@code 404}.</p>
	 *
	 * @param version        contract version, e.g. {@code 2} or {@code 3}
	 * @param sessionId      the raw session id; encoding is Feign's responsibility, not the caller's
	 * @param brand          brand discriminator, e.g. {@code LP}/{@code SB}
	 * @param tenant         client platform family, {@code web} or {@code app}
	 * @param clientPlatform the caller's {@code x-client-platform}; {@code null} omits the header
	 * @return the decoded token envelope
	 */
	@GetMapping("${auth.exchange-token-path-template}")
	AuthExchangeResponse exchangeToken(
			@PathVariable("version") int version,
			@RequestParam(SESSION_ID_PARAM) String sessionId,
			@RequestHeader(BRAND_HEADER) String brand,
			@RequestHeader("tenant") String tenant,
			@RequestHeader(value = CLIENT_PLATFORM_HEADER, required = false) String clientPlatform);
}
