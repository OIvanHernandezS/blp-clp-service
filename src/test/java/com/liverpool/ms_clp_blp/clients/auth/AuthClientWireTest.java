package com.liverpool.ms_clp_blp.clients.auth;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.liverpool.ms_clp_blp.clients.auth.dto.AuthExchangeResponse;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level contract for {@link AuthClient}, pinning how the session id reaches the network.
 *
 * <h3>Why this test exists</h3>
 * <p>A {@code SessionId} is a base64 signature and routinely contains {@code +} and {@code /}. If
 * either reaches the auth service unencoded, it reads the {@code +} as a space and answers
 * {@code 401}. {@code AuthTokenExchangeAdapter} swallows that and returns
 * {@code AuthExchangeResult.GUEST}, so the symptom in production is not an error — it is every
 * logged-in user silently being served the guest experience. Nothing else in the suite would catch
 * that, and no mock can: it is a property of the bytes on the wire.</p>
 *
 * <p>These assertions inspect the raw query string WireMock received, not a parsed value, because
 * parsing is exactly the step that would hide the bug.</p>
 *
 * <h3>And which host the call reaches</h3>
 * <p>A second server stands in for a brand's own origin. {@link AuthBrandHostInterceptor} re-targets
 * the request there, which no mock can verify: it is a property of the socket the bytes arrive on, and
 * it depends on Feign applying an interceptor's {@code target()} in preference to the client's
 * hard-coded {@code url}. The encoding assertions are repeated against the re-targeted call because
 * re-targeting re-parses the URL — the two contracts are only independent until they are not.</p>
 */
@ServiceCredential
@SpringBootTest(properties = "management.server.port=0")
class AuthClientWireTest {

	/** Contains both characters that break if passed through unencoded. */
	private static final String SESSION_ID = "abc+def/ghi==";

	private static final String EXCHANGE_PATH = "/v2/auth/exchange-token";

	private static final String EXCHANGE_V3_PATH = "/v3/auth/exchange-token";

	/** The brand routed to its own origin here; {@code LP} and {@code SB} deliberately keep the default. */
	private static final String OVERRIDDEN_BRAND = "WE";

	/** Path prefix on the brand origin, proving {@code target()} keeps it (base URLs carry one in prod). */
	private static final String BRAND_PREFIX = "/auth-service";

	private static WireMockServer wireMock;

	/** Stands in for the brand's own origin, e.g. {@code ogcp-cms-site-d.suburbia.com.mx}. */
	private static WireMockServer brandWireMock;

	@Autowired
	private AuthClient authClient;

	@BeforeAll
	static void startServer() {
		wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wireMock.start();
		brandWireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		brandWireMock.start();
	}

	@AfterAll
	static void stopServer() {
		wireMock.stop();
		brandWireMock.stop();
	}

	@BeforeEach
	void resetStubs() {
		stubExchange(wireMock);
		stubExchange(brandWireMock);
	}

	private static void stubExchange(WireMockServer server) {
		server.resetAll();
		server.stubFor(get(anyUrl()).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("""
						{ "decodeAccessToken": { "isAnonymous": false, "prn": "profile-1" } }
						""")));
	}

	@DynamicPropertySource
	static void authBaseUrl(DynamicPropertyRegistry registry) {
		registry.add("auth.base-url", () -> "http://localhost:" + wireMock.port());
		registry.add("auth.brand-base-urls." + OVERRIDDEN_BRAND,
				() -> "http://localhost:" + brandWireMock.port() + BRAND_PREFIX);
	}

	/**
	 * The load-bearing assertion: {@code +} must never reach the wire raw.
	 *
	 * <p>This is the character behind the original production defect — the auth service applies
	 * form-encoding semantics and reads a raw {@code +} as a space, then rejects the signature with
	 * {@code 401}.</p>
	 */
	@Test
	void exchangeToken_percentEncodesPlusInTheSessionId() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		List<LoggedRequest> requests = wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH)));
		assertThat(requests).hasSize(1);

		assertThat(requests.get(0).getUrl())
				.as("a raw '+' is read as a space by the auth service and rejected with 401")
				.contains("%2B")
				.doesNotContain("+");
	}

	/** {@code =} closes a query pair, so base64 padding must be escaped. */
	@Test
	void exchangeToken_percentEncodesBase64PaddingInTheSessionId() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))).get(0).getUrl())
				.contains("%3D%3D");
	}

	/**
	 * Documents a deliberate, verified difference from the superseded {@code RestClient} call.
	 *
	 * <p>Spring's URI-variable expansion applied strict per-component encoding and emitted {@code %2F}
	 * for a slash. Feign leaves it raw, which RFC 3986 explicitly permits: the query component is
	 * defined as {@code *( pchar / "/" / "?" )}, so {@code /} is a legal query character. Query parsing
	 * splits on {@code &} and {@code =} only, so a slash in the value is unambiguous and round-trips
	 * intact — unlike {@code +}, it carries no alternate form-encoding meaning.</p>
	 *
	 * <p>Asserted rather than ignored so the difference is a recorded decision: if the auth service
	 * ever turns out to mishandle a raw slash, this test names the reason and the fix belongs in
	 * {@link AuthClient}.</p>
	 */
	@Test
	void exchangeToken_leavesSlashRaw_whichRfc3986PermitsInAQuery() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))).get(0).getUrl())
				.contains("def/ghi");
	}

	/** Whatever the encoding, the value must survive the round trip exactly. */
	@Test
	void exchangeToken_sessionIdRoundTripsToTheServerUnchanged() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))).get(0)
				.queryParameter("SessionId").firstValue())
				.isEqualTo(SESSION_ID);
	}

	/** The parameter name comes from {@code auth.session-cookie-name}; a broken placeholder is silent. */
	@Test
	void exchangeToken_sendsTheSessionIdUnderTheConfiguredParameterName() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		assertThat(wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))).get(0).getUrl())
				.startsWith(EXCHANGE_PATH + "?SessionId=");
	}

	@Test
	void exchangeToken_sendsBrandAndTenantHeaders_andDeserializesResponse() {
		AuthExchangeResponse response = authClient.exchangeToken(2, SESSION_ID, "SB", "app", null);

		assertThat(response).isNotNull();
		assertThat(response.decodeAccessToken()).isNotNull();
		assertThat(response.decodeAccessToken().isAnonymous()).isFalse();
		assertThat(response.decodeAccessToken().prn()).isEqualTo("profile-1");

		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))
				.withHeader("x-brand-id", equalTo("SB"))
				.withHeader("tenant", equalTo("app")));
	}

	/**
	 * The workaround's reason for existing: the brand's exchange must leave on the brand's own origin.
	 *
	 * <p>Also pins that the origin's path prefix survives — {@code RequestTemplate.target()} keeps
	 * {@code rawPath}, so {@code /auth-service} is still in front of {@code /v2/auth/exchange-token}.
	 * Lose that and every call 404s, and a 404 here reads as "everyone is a guest".</p>
	 */
	@Test
	void exchangeToken_reTargetsToTheBrandOrigin_whenOneIsConfigured() {
		authClient.exchangeToken(2, SESSION_ID, OVERRIDDEN_BRAND, "web", null);

		brandWireMock.verify(getRequestedFor(urlPathEqualTo(BRAND_PREFIX + EXCHANGE_PATH))
				.withHeader("x-brand-id", equalTo(OVERRIDDEN_BRAND)));
		assertThat(wireMock.findAll(getRequestedFor(anyUrl())))
				.as("the default origin must not also be called")
				.isEmpty();
	}

	/**
	 * The fallback, which is what keeps this change inert wherever it is not configured: no override
	 * means the interceptor leaves the template alone and Feign's hard-coded target still wins.
	 */
	@Test
	void exchangeToken_keepsTheDefaultOrigin_forABrandWithNoOverride() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);

		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH)));
		assertThat(brandWireMock.findAll(getRequestedFor(anyUrl()))).isEmpty();
	}

	/**
	 * Re-targeting re-parses the URL and its query templates, so the encoding contract above has to be
	 * re-proved on the re-targeted path rather than assumed to carry over.
	 */
	@Test
	void exchangeToken_preservesSessionIdEncoding_whenReTargeted() {
		authClient.exchangeToken(2, SESSION_ID, OVERRIDDEN_BRAND, "web", null);

		List<LoggedRequest> requests =
				brandWireMock.findAll(getRequestedFor(urlPathEqualTo(BRAND_PREFIX + EXCHANGE_PATH)));
		assertThat(requests).hasSize(1);
		assertThat(requests.get(0).getUrl())
				.contains("%2B")
				.contains("%3D%3D")
				.doesNotContain("+");
		assertThat(requests.get(0).queryParameter("SessionId").firstValue()).isEqualTo(SESSION_ID);
	}

	// ── versioned contract (x-auth-version) ──────────────────────────────────────────────────────────

	@Test
	void exchangeToken_v3_reachesTheV3Path_withBrandTenantAndPlatform() {
		AuthExchangeResponse response = authClient.exchangeToken(3, SESSION_ID, "LP", "app", "android");

		assertThat(response.decodeAccessToken().prn()).isEqualTo("profile-1");
		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_V3_PATH))
				.withHeader("x-brand-id", equalTo("LP"))
				.withHeader("tenant", equalTo("app"))
				.withHeader(AuthClient.CLIENT_PLATFORM_HEADER, equalTo("android")));
	}

	/** The session id encoding contract holds on the v3 path too — it is a separate mapping. */
	@Test
	void exchangeToken_v3_percentEncodesTheSessionId() {
		authClient.exchangeToken(3, SESSION_ID, "LP", "web", "web");

		List<LoggedRequest> requests = wireMock.findAll(getRequestedFor(urlPathEqualTo(EXCHANGE_V3_PATH)));
		assertThat(requests).hasSize(1);
		assertThat(requests.get(0).getUrl()).contains("%2B").doesNotContain("+");
		assertThat(requests.get(0).queryParameter("SessionId").firstValue()).isEqualTo(SESSION_ID);
	}

	/**
	 * A caller that sent no platform must not produce an empty {@code x-client-platform}: the auth
	 * service would read a present-but-blank header as a platform, not as its absence.
	 */
	@Test
	void exchangeToken_nullPlatform_omitsTheHeader_onBothContracts() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", null);
		authClient.exchangeToken(3, SESSION_ID, "LP", "web", null);

		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))
				.withHeader(AuthClient.CLIENT_PLATFORM_HEADER, absent()));
		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_V3_PATH))
				.withHeader(AuthClient.CLIENT_PLATFORM_HEADER, absent()));
	}

	/** Same as digital_bff: the platform is forwarded on v2 as well when the caller sent it. */
	@Test
	void exchangeToken_v2_forwardsThePlatformWhenPresent() {
		authClient.exchangeToken(2, SESSION_ID, "LP", "web", "web");

		wireMock.verify(getRequestedFor(urlPathEqualTo(EXCHANGE_PATH))
				.withHeader(AuthClient.CLIENT_PLATFORM_HEADER, equalTo("web")));
	}

	/**
	 * The version is a path variable, not a fixed mapping: a contract released later is one more value of
	 * {@code AUTH_SUPPORTED_VERSIONS}, and the client builds its path with no code change.
	 */
	@Test
	void exchangeToken_buildsThePathFromTheVersion() {
		authClient.exchangeToken(4, SESSION_ID, "LP", "web", null);

		wireMock.verify(getRequestedFor(urlPathEqualTo("/v4/auth/exchange-token")));
	}

	/** The per-brand origin keeps its path prefix in front of the v3 path as well. */
	@Test
	void exchangeToken_v3_reTargetsToTheBrandOrigin_whenOneIsConfigured() {
		authClient.exchangeToken(3, SESSION_ID, OVERRIDDEN_BRAND, "web", "web");

		brandWireMock.verify(getRequestedFor(urlPathEqualTo(BRAND_PREFIX + EXCHANGE_V3_PATH))
				.withHeader("x-brand-id", equalTo(OVERRIDDEN_BRAND)));
		assertThat(wireMock.findAll(getRequestedFor(anyUrl()))).isEmpty();
	}
}
