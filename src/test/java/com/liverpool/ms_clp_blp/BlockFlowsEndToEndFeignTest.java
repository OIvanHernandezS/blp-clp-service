package com.liverpool.ms_clp_blp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;
import com.liverpool.ms_clp_blp.shared.web.TestServiceCredential;

import tools.jackson.databind.ObjectMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end validation over Feign of the block flow with the most moving parts: {@code products_list}
 * — four providers behind one port and one batch endpoint.
 *
 * <p>It runs through the full stack — controller, use case, circuit breaker, adapter, Feign proxy,
 * real socket — against stubbed upstreams. {@code products_list} takes its Salesforce identity from
 * the {@code x-sf-user-id} header, gated on the {@code SessionId} cookie (ADR-041); without it a
 * Salesforce carousel silently resolves to nothing, which is exactly the failure mode a mock would
 * hide.</p>
 */
@ServiceCredential
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"management.server.port=0",
				// The carousels client authenticates with Basic credentials built by commons'
				// interceptor; leaving them unset fails the call rather than sending an empty header.
				"salesforce.auth.user=e2e-user",
				"salesforce.auth.pass=e2e-pass",
				// Contentstack is the only live CMS source (ADR-053); LP's stack must be credentialed
				// or every CMS read here is a 502 before a block is ever resolved.
				"api.content-stack.brands.LP.api-key=api-key-lp",
				"api.content-stack.brands.LP.access-token=delivery-token-lp"
		})
class BlockFlowsEndToEndFeignTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Shaped like the real cookie: the browser stores the session id <em>already</em> percent-encoded,
	 * so the value carries literal {@code %2F}/{@code %2B} rather than raw {@code /}/{@code +}. The
	 * auth exchange sends it as a query parameter and re-encodes the {@code %} to {@code %25}; get
	 * that wrong and every logged-in caller is silently served the guest experience.
	 *
	 * <p>Overridable with {@code -Dtest.sessionId=...} to replay a real session locally; the default is
	 * synthetic on purpose, so no live token is ever committed.</p>
	 */
	private static final String SESSION_ID = System.getProperty(
			"test.sessionId", "f2207b1b-a5f4-47cd-b70a-fcf4072c2d04.O1iC5ptdRvyptqS19fB%2Fsynthetic%2B%2Bvalue");

	/** products_list sends this to Salesforce as ID_ATG1; see ADR-041. */
	private static final String SF_USER_ID_HEADER = "x-sf-user-id";
	private static final String SF_USER_ID = "31216912118";

	/** Three products: ProductsListUseCases enforces Jewel's minimum_items (default 3) per ADR-029. */
	private static final String JEWEL_PRODUCTS = """
			[ { "title": "Jewel One",   "standard_features": { "offer_id": "JW-1", "sale_price": 10.0 } },
			  { "title": "Jewel Two",   "standard_features": { "offer_id": "JW-2", "sale_price": 20.0 } },
			  { "title": "Jewel Three", "standard_features": { "offer_id": "JW-3", "sale_price": 30.0 } } ]
			""";

	private static WireMockServer wireMock;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startUpstreams() throws IOException {
		wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wireMock.start();

		// Auth exchange, for any flow that resolves identity.
		wireMock.stubFor(get(urlPathEqualTo("/v2/auth/exchange-token")).willReturn(json("""
				{ "accessToken": "e2e-access-token",
				  "decodeAccessToken": { "isAnonymous": false, "prn": "atg-profile-1" } }
				""")));
		// Same envelope on the v3 contract, selected per request with x-auth-version: 3.
		wireMock.stubFor(get(urlPathEqualTo("/v3/auth/exchange-token")).willReturn(json("""
				{ "accessToken": "e2e-access-token",
				  "decodeAccessToken": { "isAnonymous": false, "prn": "atg-profile-1" } }
				""")));

		// Salesforce Evergage — one endpoint, the action in the body selects the campaign.
		wireMock.stubFor(post(urlPathEqualTo("/api2/authevent/liverpool"))
				.willReturn(json("""
						{ "campaignResponses": [ { "campaignName": "Carousel", "payload": {
						    "title": "Ofertas", "productDataMapped": [
						      { "productId": "SF-1", "title": "Salesforce One" } ] } } ] }
						""")));

		// JewelML
		wireMock.stubFor(get(urlPathMatching("/c/p/.*/l")).willReturn(json(JEWEL_PRODUCTS)));

		// Vertex (ms_plp_search)
		wireMock.stubFor(post(urlPathEqualTo("/v2/search")).willReturn(json("""
				{ "data": { "records": [ { "productId": "VX-1", "title": "Vertex One" } ] } }
				""")));

		wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(json("{}")));
	}

	@AfterAll
	static void stopUpstreams() {
		wireMock.stop();
	}

	@DynamicPropertySource
	static void pointEveryClientAtWireMock(DynamicPropertyRegistry registry) {
		registry.add("api.content-stack.url", BlockFlowsEndToEndFeignTest::url);
		registry.add("auth.base-url", BlockFlowsEndToEndFeignTest::url);
		// products_list talks to Salesforce through commons' client, whose url is the FULL endpoint.
		registry.add("api.salesforce-carrousels.url",
				() -> url() + "/api2/authevent/liverpool");
		registry.add("vertex.base-url", BlockFlowsEndToEndFeignTest::url);
		registry.add("api.jewel.url", BlockFlowsEndToEndFeignTest::url);
		registry.add("api.jewel.lp-integration-id", () -> "lp-integration-id");
	}

	private static String url() {
		return "http://localhost:" + wireMock.port();
	}

	// ── products_list: all three providers behind one port ────────────────────────────────────────

	@Test
	@SuppressWarnings("unchecked")
	void productsList_resolvesSalesforceJewelAndVertexInOneBatch() throws Exception {
		String body = """
				[
				  { "uid": "blk-sf", "source_of_data": "salesforce", "salesforce_carousel": "CMSOfertasIncreibles" },
				  { "uid": "blk-jw", "source_of_data": "jewel",
				    "jewel_model_config": { "jewel_model": "global-tps-sales" } },
				  { "uid": "blk-vx", "source_of_data": "vertex",
				    "products_data": { "category": "cat-1" } }
				]
				""";

		HttpResponse<String> response = httpPost("/blp/blocks/products-list", body);
		assertThat(response.statusCode()).isEqualTo(200);

		Map<String, Object> parsed = MAPPER.readValue(response.body(), Map.class);
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) parsed.get("blocks");

		// Order is the contract: one entry per requested block, in request order.
		assertThat(blocks).hasSize(3);
		assertThat(blocks).extracting(b -> b.get("blockId")).containsExactly("blk-sf", "blk-jw", "blk-vx");

		// Each provider actually produced its own products — proving all three adapters resolved,
		// rather than one succeeding and the rest silently degrading to empty.
		assertThat(productIds(blocks.get(0))).contains("SF-1");
		assertThat(productIds(blocks.get(1))).contains("JW-1");
		assertThat(productIds(blocks.get(2))).contains("VX-1");

		// And each really went out over HTTP to its own upstream.
		wireMock.verify(postRequestedFor(urlPathEqualTo("/api2/authevent/liverpool")));
		wireMock.verify(getRequestedFor(urlPathMatching("/c/p/.*/l")));
		wireMock.verify(postRequestedFor(urlPathEqualTo("/v2/search")));

		// ADR-041: the x-sf-user-id header is what reaches Salesforce as ID_ATG1 — not the profile id
		// the auth exchange returned ("atg-profile-1"), which is what every other Salesforce block uses.
		wireMock.verify(postRequestedFor(urlPathEqualTo("/api2/authevent/liverpool"))
				.withRequestBody(containing("\"ID_ATG1\":\"" + SF_USER_ID + "\"")));
	}

	/** A provider outage must degrade that block only, never the batch. */
	@Test
	@SuppressWarnings("unchecked")
	void productsList_oneProviderDown_doesNotBreakTheOthers() throws Exception {
		wireMock.stubFor(get(urlPathMatching("/c/p/.*/l")).atPriority(1)
				.willReturn(aResponse().withStatus(500)));
		try {
			HttpResponse<String> response = httpPost("/blp/blocks/products-list", """
					[
					  { "uid": "blk-sf", "source_of_data": "salesforce",
					    "salesforce_carousel": "CMSOfertasIncreibles" },
					  { "uid": "blk-jw", "source_of_data": "jewel",
					    "jewel_model_config": { "jewel_model": "global-tps-sales" } }
					]
					""");

			assertThat(response.statusCode()).isEqualTo(200);
			Map<String, Object> parsed = MAPPER.readValue(response.body(), Map.class);
			List<Map<String, Object>> blocks = (List<Map<String, Object>>) parsed.get("blocks");

			assertThat(blocks.get(0)).containsEntry("status", "AVAILABLE");
			assertThat(productIds(blocks.get(0))).contains("SF-1");

			// UNAVAILABLE, not EMPTY: this one is worth retrying later, and the reason says which of
			// the two failure modes it was (ADR-043).
			assertThat(blocks.get(1))
					.containsEntry("status", "UNAVAILABLE")
					.containsEntry("reason", "UPSTREAM_FAILED")
					.doesNotContainKey("content");
		} finally {
			wireMock.stubFor(get(urlPathMatching("/c/p/.*/l")).atPriority(1).willReturn(json(JEWEL_PRODUCTS)));
		}
	}

	/**
	 * The reported bug, end to end over a real socket: a carousel whose provider answers with fewer
	 * products than its {@code min_of_products} comes back {@code EMPTY} naming {@code BELOW_MIN_PRODUCTS},
	 * so the app drops the block instead of retrying it (ADR-043).
	 *
	 * <p>Salesforce is stubbed with one product against a {@code min_of_products} of 6 — the same shape as
	 * the {@code CMSTePuedeGustar} request in the original report.</p>
	 */
	@Test
	@SuppressWarnings("unchecked")
	void productsList_belowMinOfProducts_reportsEmptyWithTheReason() throws Exception {
		HttpResponse<String> response = httpPost("/blp/blocks/products-list", """
				[
				  { "uid": "blk-sf", "source_of_data": "salesforce",
				    "salesforce_carousel": "CMSOfertasIncreibles",
				    "min_of_products": 6, "max_of_products": 10 }
				]
				""");

		assertThat(response.statusCode())
				.as("a carousel below its minimum is a business outcome, not an error").isEqualTo(200);
		Map<String, Object> parsed = MAPPER.readValue(response.body(), Map.class);
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) parsed.get("blocks");

		assertThat(blocks).hasSize(1);
		assertThat(blocks.getFirst())
				.containsEntry("blockId", "blk-sf")
				.containsEntry("status", "EMPTY")
				.containsEntry("reason", "BELOW_MIN_PRODUCTS")
				.doesNotContainKey("content");

		// The call really was made — this is a threshold rejection, not a skipped upstream.
		wireMock.verify(postRequestedFor(urlPathEqualTo("/api2/authevent/liverpool")));
	}

	/** A resolved carousel omits `reason` entirely, so its presence always means something happened. */
	@Test
	@SuppressWarnings("unchecked")
	void productsList_resolvedCarousel_omitsTheReason() throws Exception {
		HttpResponse<String> response = httpPost("/blp/blocks/products-list", """
				[
				  { "uid": "blk-sf", "source_of_data": "salesforce",
				    "salesforce_carousel": "CMSOfertasIncreibles" }
				]
				""");

		assertThat(response.statusCode()).isEqualTo(200);
		Map<String, Object> parsed = MAPPER.readValue(response.body(), Map.class);
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) parsed.get("blocks");

		assertThat(blocks.getFirst())
				.containsEntry("status", "AVAILABLE")
				.doesNotContainKey("reason");
	}

	// ── helpers ───────────────────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static List<String> productIds(Map<String, Object> block) {
		Map<String, Object> content = (Map<String, Object>) block.get("content");
		Object products = content == null ? null : content.get("products");
		if (!(products instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().map(p -> String.valueOf(((Map<String, Object>) p).get("productId"))).toList();
	}

	/**
	 * No landing endpoint reads exchange-derived identity: the products-list endpoint resolves the
	 * session without the auth service even when a {@code SessionId} cookie travels (ADR-054/067).
	 */
	@Test
	void productsList_neverExchangesTheSessionToken() throws Exception {
		wireMock.resetRequests();

		assertThat(httpPost("/blp/blocks/products-list", """
				[ { "uid": "blk-vx", "source_of_data": "vertex", "products_data": { "category": "cat-1" } } ]
				""").statusCode()).isEqualTo(200);

		wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v2/auth/exchange-token")));
		wireMock.verify(0, getRequestedFor(urlPathEqualTo("/v3/auth/exchange-token")));
	}

	/** The CLP route serves the very same batch endpoint. */
	@Test
	void productsList_isServedOnTheClpRouteToo() throws Exception {
		assertThat(httpPost("/clp/blocks/products-list", """
				[ { "uid": "blk-vx", "source_of_data": "vertex", "products_data": { "category": "cat-1" } } ]
				""").statusCode()).isEqualTo(200);
	}

	private HttpResponse<String> httpPost(String path, String body) throws Exception {
		return send(HttpRequest.newBuilder().uri(uri(path))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)));
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		HttpRequest request = builder
				// A real socket, so @WithMockUser cannot apply: the inbound Basic credential has to
				// travel like any caller's would, or every assertion below reads 401 instead.
				.header("Authorization", TestServiceCredential.AUTHORIZATION_HEADER)
				.header("Cookie", "SessionId=" + SESSION_ID)
				// The web frontend's channel.
				.header("x-channel", "WEB")
				// The caller's login declaration; landing endpoints never exchange the cookie above.
				.header("x-is-logged-in", "true")
				// products_list' Salesforce identity (ADR-041): the ATG profile id travels in this
				// header, and only counts because the SessionId cookie above travelled with it.
				.header(SF_USER_ID_HEADER, SF_USER_ID)
				.timeout(Duration.ofSeconds(30))
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}

}
