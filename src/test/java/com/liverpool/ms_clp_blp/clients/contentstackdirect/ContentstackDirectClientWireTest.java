package com.liverpool.ms_clp_blp.clients.contentstackdirect;

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
import com.liverpool.clients.ContentStackClient;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level contract for the direct Contentstack Delivery API call.
 *
 * <p>Every assertion here is a parameter the content-service sends and the fallback must send too. It
 * is pinned against a real socket rather than reviewed, because the failure mode of getting one wrong
 * is not an error: the call succeeds and quietly returns a <em>differently populated</em> entry —
 * references unresolved past the default depth, embedded items missing, an unlocalised entry omitted
 * instead of falling back. That would surface as a subtly broken page during an outage.</p>
 *
 * <p>{@code include_embedded_items[]} is the one to watch. Feign percent-encodes the brackets in the
 * parameter <em>name</em>; the SDK's {@code qs} serialiser writes them literally. Contentstack is
 * expected to accept both, but that expectation is exactly what this test exists to check, and it
 * should be re-confirmed against a real QA stack before this ships to production.</p>
 */
@ServiceCredential
@SpringBootTest(properties = "management.server.port=0")
class ContentstackDirectClientWireTest {

	private static final String ENTRIES_PATH = "/v3/content_types/page/entries";

	private static WireMockServer wireMock;

	@Autowired
	private ContentStackClient contentStackClient;

	@BeforeAll
	static void startServer() {
		wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wireMock.start();
	}

	@AfterAll
	static void stopServer() {
		wireMock.stop();
	}

	@BeforeEach
	void resetStubs() {
		wireMock.resetAll();
		wireMock.stubFor(get(anyUrl()).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody("{\"entries\":[{\"uid\":\"blt1\",\"url\":\"/home\"}]}")));
	}

	@DynamicPropertySource
	static void contentStackBaseUrl(DynamicPropertyRegistry registry) {
		registry.add("api.content-stack.url", () -> "http://localhost:" + wireMock.port());
	}

	@Test
	void getEntriesByQueryWithIncludes_sendsCredentialsAsHeadersAndEnvironmentAsAParameter() {
		callWithQuery("{\"url\":{\"$in\":[\"/home\",\"home\"]}}");

		wireMock.verify(getRequestedFor(urlPathEqualTo(ENTRIES_PATH))
				.withHeader("api_key", equalTo("api-key-lp"))
				.withHeader("access_token", equalTo("delivery-token-lp"))
				.withHeader("branch", equalTo("qa"))
				.withQueryParam("environment", equalTo("qa")));
	}

	/**
	 * The full include set. Depth is capped at 3 (Contentstack's enforced maximum) even though the
	 * proxy still requests 5 — see {@link ContentStackDirectGateway#INCLUDE_ALL_DEPTH}. Every other
	 * include mirrors the proxy; a missing one changes which fields come back populated.
	 */
	@Test
	void getEntriesByQueryWithIncludes_sendsTheProxysFullIncludeSet() {
		callWithQuery(null);

		wireMock.verify(getRequestedFor(urlPathEqualTo(ENTRIES_PATH))
				.withQueryParam("include_all", equalTo("true"))
				.withQueryParam("include_all_depth", equalTo("3"))
				.withQueryParam("include_dimension", equalTo("true"))
				.withQueryParam("include_fallback", equalTo("true"))
				.withQueryParam("include_embedded_items[]", equalTo("BASE"))
				.withQueryParam("locale", equalTo("es-mx"))
				.withQueryParam("limit", equalTo("1")));
	}

	/** The query travels as one JSON string, which is how the SDK sends it. */
	@Test
	void getEntriesByQueryWithIncludes_sendsTheQueryAsASingleJsonParameter() {
		String query = "{\"taxonomies.brand\":\"gap\",\"url\":{\"$in\":[\"/home\",\"home\"]}}";

		callWithQuery(query);

		wireMock.verify(getRequestedFor(urlPathEqualTo(ENTRIES_PATH))
				.withQueryParam("query", equalTo(query)));
	}

	/** A null query means "no filtering at all" and must not appear on the wire as the string "null". */
	@Test
	void getEntriesByQueryWithIncludes_omitsTheQueryParameterWhenThereIsNoQuery() {
		callWithQuery(null);

		wireMock.verify(getRequestedFor(urlPathEqualTo(ENTRIES_PATH))
				.withoutQueryParam("query"));
	}

	@Test
	void getEntriesByQueryWithIncludes_readsTheEntriesEnvelope() {
		var body = callWithQuery(null).getBody();

		assertThat(body).containsKey("entries");
	}

	/**
	 * @param query the raw query JSON, or null
	 * @return the client's response
	 */
	private org.springframework.http.ResponseEntity<java.util.Map<String, Object>> callWithQuery(String query) {
		return contentStackClient.getEntriesByQueryWithIncludes(
				"page", "qa", "api-key-lp", "delivery-token-lp", "qa", "es-mx", query,
				true, 3, true, true, "BASE", 1);
	}
}
