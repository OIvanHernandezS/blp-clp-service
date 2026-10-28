package com.liverpool.ms_clp_blp;

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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider kill switches, end to end: with all three flags off, {@code POST
 * /blp/blocks/products-list} must answer {@code EMPTY}/{@code FEATURE_FLAG_OFF} and
 * <strong>no provider may be contacted over HTTP</strong>.
 *
 * <p>That last assertion is the reason this test exists and the reason it cannot be a unit test: the
 * reported bug was a carousel still serving Salesforce products after its flag had been switched off,
 * and only a run through the real stack — controller, use case, {@code FeatureFlagAdapter},
 * {@code landing.feature-flags.*}, Feign, socket — proves the call is not made. {@code
 * BlockFlowsEndToEndFeignTest} is the mirror image of this class, asserting the same three providers
 * <em>are</em> called when their flags are on; the flags are set per class because
 * {@code @SpringBootTest} properties cannot vary per test.</p>
 *
 * <p>The three flags are switched off through {@code landing.feature-flags.*} rather than through Mongo
 * because that is the layer under this service's control in a test: {@code FeatureFlagAdapter} falls
 * back to it when a brand publishes no override, and the per-brand store is exercised by
 * {@code BrandFlagsFlowTest}. The gate under test does not care which layer answered.</p>
 */
@ServiceCredential
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"management.server.port=0",
				"salesforce.auth.user=e2e-user",
				"salesforce.auth.pass=e2e-pass",
				"api.content-stack.brands.LP.api-key=api-key-lp",
				"api.content-stack.brands.LP.access-token=delivery-token-lp",
				// The subject of this test: every products_list provider switched off at once.
				"landing.feature-flags.products-list-salesforce=false",
				"landing.feature-flags.jewel=false",
				"landing.feature-flags.vertex=false"
		})
class ProductsListFeatureFlagEndToEndTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String SESSION_ID =
			"f2207b1b-a5f4-47cd-b70a-fcf4072c2d04.O1iC5ptdRvyptqS19fB%2Fsynthetic%2B%2Bvalue";

	/** products_list sends this to Salesforce as ID_ATG1; see ADR-041. */
	private static final String SF_USER_ID_HEADER = "x-sf-user-id";
	private static final String SF_USER_ID = "31216912118";

	private static WireMockServer wireMock;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startUpstreams() {
		wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wireMock.start();

		// An authenticated session, so nothing in the response can be blamed on a guest: were the gate
		// missing, all three providers would have everything they need to resolve.
		wireMock.stubFor(get(urlPathEqualTo("/v2/auth/exchange-token")).willReturn(json("""
				{ "accessToken": "e2e-access-token",
				  "decodeAccessToken": { "isAnonymous": false, "prn": "atg-profile-1" } }
				""")));

		wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(json("{}")));
	}

	@AfterAll
	static void stopUpstreams() {
		wireMock.stop();
	}

	@DynamicPropertySource
	static void pointEveryClientAtWireMock(DynamicPropertyRegistry registry) {
		registry.add("api.content-stack.url", ProductsListFeatureFlagEndToEndTest::url);
		registry.add("auth.base-url", ProductsListFeatureFlagEndToEndTest::url);
		registry.add("salesforce.base-url", ProductsListFeatureFlagEndToEndTest::url);
		registry.add("api.salesforce-carrousels.url", () -> url() + "/api2/authevent/liverpool");
		registry.add("vertex.base-url", ProductsListFeatureFlagEndToEndTest::url);
		registry.add("api.jewel.url", ProductsListFeatureFlagEndToEndTest::url);
		registry.add("api.jewel.lp-integration-id", () -> "lp-integration-id");
	}

	private static String url() {
		return "http://localhost:" + wireMock.port();
	}

	@Test
	@SuppressWarnings("unchecked")
	void productsList_everyProviderSwitchedOff_isEmptyAndCallsNoProvider() throws Exception {
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

		// A switched-off block is a business outcome, not an error: still 200, still one entry per block.
		assertThat(response.statusCode()).isEqualTo(200);

		Map<String, Object> parsed = MAPPER.readValue(response.body(), Map.class);
		List<Map<String, Object>> blocks = (List<Map<String, Object>>) parsed.get("blocks");

		assertThat(blocks).hasSize(3);
		assertThat(blocks).extracting(b -> b.get("blockId")).containsExactly("blk-sf", "blk-jw", "blk-vx");
		assertThat(blocks).allSatisfy(block -> {
			assertThat(block.get("status")).isEqualTo("EMPTY");
			assertThat(block.get("reason")).isEqualTo("FEATURE_FLAG_OFF");
			assertThat(block).doesNotContainKey("content");
		});

		// The point of the whole change: not one of the three providers was contacted.
		wireMock.verify(0, postRequestedFor(urlPathEqualTo("/api2/authevent/liverpool")));
		wireMock.verify(0, getRequestedFor(urlPathMatching("/c/p/.*/l")));
		wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v2/search")));
	}

	private HttpResponse<String> httpPost(String path, String body) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Content-Type", "application/json")
				.header("Authorization", TestServiceCredential.AUTHORIZATION_HEADER)
				.header("Cookie", "SessionId=" + SESSION_ID)
				.header("x-channel", "WEB")
				.header(SF_USER_ID_HEADER, SF_USER_ID)
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}
}
