package com.liverpool.ms_clp_blp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that a landing page composes over real sockets: HTTP in at the controller, through
 * the use case, composition and every outbound adapter, out to stubbed Contentstack, Vertex, the
 * category indexer, store-service and the blacklist, and back as digital_bff's {@code page-blp} shape.
 *
 * <p>The page is the real {@code page_blp} entry digital_bff served for
 * {@code /tienda/esteelauder/catst26617625} (captured from the content-service, header/footer trimmed
 * to a reference each), so the assertions are about production content: two {@code product_list}
 * wrappers — a guest Vertex carousel and an {@code all}-audience Salesforce one — six containers and a
 * hero slider, plus the app-only sections a BLP strips.</p>
 */
@ServiceCredential
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"management.server.port=0",
				"cms.default-brand=LP",
				"api.content-stack.brands.LP.api-key=api-key-lp",
				"api.content-stack.brands.LP.access-token=delivery-token-lp",
				// Every test must reach the stubs; the cache would answer the second call.
				"cms.landing-cache.enabled=false",
				"salesforce.auth.user=e2e-user",
				"salesforce.auth.pass=e2e-pass"})
class LandingEndToEndFeignTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private static final String BLP = "/blp/es-mx/tienda/esteelauder/catst26617625";
	private static final String PAGE_BLP_ENTRIES = "/v3/content_types/page_blp/entries";
	private static final String PAGE_CLP_ENTRIES = "/v3/content_types/page_clp/entries";
	private static final String REDIRECT_ENTRIES = "/v3/content_types/category_redirect/entries";
	private static final String INDEXER_PATH = "/search-category/LP/catst26617625";
	private static final String BLACKLIST_PATH = "/catalog-ingestion-service/v1/blacklist/LIVERPOOL";

	/** The guest Vertex carousel authored on the page (products_data.category = catst26617625). */
	private static final String VERTEX_BLOCK_UID = "blt6325518c968643cd";
	/** The Salesforce carousel authored on the page; a guest has no profile id to resolve it with. */
	private static final String SALESFORCE_BLOCK_UID = "blt55d3e3dd19e0005f";

	private static WireMockServer wireMock;

	@LocalServerPort
	private int port;

	@BeforeAll
	static void startUpstreams() throws IOException {
		wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wireMock.start();
	}

	@BeforeEach
	void stubUpstreams() throws IOException {
		wireMock.resetAll();
		String entry = fixture("page-blp-catst26617625.json");

		wireMock.stubFor(get(urlPathEqualTo(PAGE_BLP_ENTRIES))
				.willReturn(json("{\"entries\":[" + entry + "]}")));
		wireMock.stubFor(get(urlPathEqualTo(PAGE_CLP_ENTRIES)).willReturn(json("{\"entries\":[]}")));
		wireMock.stubFor(get(urlPathEqualTo(REDIRECT_ENTRIES)).willReturn(json("{\"entries\":[]}")));
		wireMock.stubFor(post(urlPathEqualTo(INDEXER_PATH))
				.willReturn(json(fixture("category-tree-catst26617625.json"))));
		wireMock.stubFor(post(urlPathEqualTo("/v2/search"))
				.willReturn(json(fixture("vertex-search-catst26617625.json"))));
		wireMock.stubFor(get(urlPathEqualTo("/brands/LP/stores/7"))
				.willReturn(json("{\"storeId\":7,\"name\":\"Liverpool Santa Fe\",\"brand\":\"LP\"}")));
		wireMock.stubFor(get(urlPathEqualTo(BLACKLIST_PATH))
				.willReturn(json("{\"products\":[\"1190634101\"]}")));
		wireMock.stubFor(any(anyUrl()).atPriority(10).willReturn(json("{}")));
	}

	@AfterAll
	static void stopUpstreams() {
		wireMock.stop();
	}

	@DynamicPropertySource
	static void pointEveryClientAtWireMock(DynamicPropertyRegistry registry) {
		registry.add("api.content-stack.url", LandingEndToEndFeignTest::url);
		registry.add("auth.base-url", LandingEndToEndFeignTest::url);
		registry.add("vertex.base-url", LandingEndToEndFeignTest::url);
		registry.add("api.salesforce-carrousels.url", () -> url() + "/api2/authevent/liverpool");
		registry.add("api.jewel.url", LandingEndToEndFeignTest::url);
		registry.add("category-indexer.base-url", LandingEndToEndFeignTest::url);
		registry.add("store-service.base-url", LandingEndToEndFeignTest::url);
		registry.add("blacklist.base-url", LandingEndToEndFeignTest::url);
	}

	private static String url() {
		return "http://localhost:" + wireMock.port();
	}

	// ── the page ──────────────────────────────────────────────────────────────────────────────────

	@Test
	@SuppressWarnings("unchecked")
	void guestBlp_composesTheRealEntry_inTheBffShape() throws Exception {
		HttpResponse<String> response = call(BLP, "false", null);

		assertThat(response.statusCode()).isEqualTo(200);
		Map<String, Object> body = MAPPER.readValue(response.body(), Map.class);
		assertThat(body)
				.containsEntry("category_id", "catst26617625")
				.containsEntry("url", "/tienda/estee-lauder/catst26617625")
				.containsKeys("uid", "events", "header", "footer", "blocks", "categoryData")
				// The app-only sections a BLP strips, the raw layout, and the global entry.
				.doesNotContainKeys("apps_top_content", "apps_products", "apps_article", "layout",
						"globalData", "template");

		List<Map<String, Object>> blocks = (List<Map<String, Object>>) body.get("blocks");
		assertThat(blocks).extracting(block -> block.get("_content_type_uid"))
				.containsExactly("container", "hero_banner_slider", "container", "container", "container",
						"container", "container", "products_list");
		assertThat(response.headers().firstValue("x-data-origin")).hasValue("ContentStack");
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("public");
	}

	@Test
	@SuppressWarnings("unchecked")
	void guestBlp_inlinesTheVertexCarousel_withoutBlacklistedProducts() throws Exception {
		Map<String, Object> carousel = blockByUid(MAPPER.readValue(call(BLP, "false", null).body(), Map.class),
				VERTEX_BLOCK_UID);

		assertThat(carousel)
				.containsEntry("source_of_data", "vertex")
				.containsEntry("loadStrategy", "EAGER")
				.containsEntry("status", "AVAILABLE")
				.containsEntry("url", "/blp/blocks/products-list");
		List<Map<String, Object>> products = (List<Map<String, Object>>) carousel.get("products");
		assertThat(products).extracting(product -> product.get("productId"))
				.hasSize(7)
				.doesNotContain("1190634101");

		wireMock.verify(postRequestedFor(urlPathEqualTo("/v2/search"))
				.withRequestBody(equalToJson("{\"categoryId\":\"catst26617625\",\"pageSize\":20,\"brand\":\"LP\"}",
						true, true)));
	}

	/** digital_bff dropped the Salesforce carousel for a guest; so does this. */
	@Test
	@SuppressWarnings("unchecked")
	void guestBlp_dropsTheSalesforceCarouselItCannotPersonalise() throws Exception {
		Map<String, Object> body = MAPPER.readValue(call(BLP, "false", null).body(), Map.class);

		assertThat((List<Map<String, Object>>) body.get("blocks"))
				.noneMatch(block -> SALESFORCE_BLOCK_UID.equals(block.get("uid")));
	}

	@Test
	@SuppressWarnings("unchecked")
	void blp_categoryData_comesFromTheIndexer_withOnlyVisibleChildren() throws Exception {
		Map<String, Object> body = MAPPER.readValue(call(BLP, "false", null).body(), Map.class);
		Map<String, Object> category = (Map<String, Object>) body.get("categoryData");

		assertThat(category)
				.containsEntry("id", "CATST26617625")
				.containsEntry("name", "Estee Lauder")
				.containsEntry("breadcrumb", "home > Estee Lauder")
				.doesNotContainKey("redirectContent");
		assertThat((List<Map<String, Object>>) category.get("children"))
				.extracting(child -> child.get("id"))
				.containsExactly("CATST56942247");
		assertThat((Map<String, Object>) category.get("metadata"))
				.containsEntry("showpath", "BLP")
				.containsEntry("isBrand", true)
				.containsEntry("redirects", List.of());
	}

	@Test
	@SuppressWarnings("unchecked")
	void loggedInBlp_isASkeleton_andTheGuestOnlyCarouselIsGone() throws Exception {
		Map<String, Object> body = MAPPER.readValue(call(BLP, "true", null).body(), Map.class);

		// The Vertex carousel is authored audience_filter=guest, so a signed-in shopper never sees it.
		assertThat((List<Map<String, Object>>) body.get("blocks"))
				.noneMatch(block -> VERTEX_BLOCK_UID.equals(block.get("uid")));
		wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v2/search")));
	}

	@Test
	@SuppressWarnings("unchecked")
	void loggedInBlp_footerMenuItems_areFilteredForTheAudience() throws Exception {
		Map<String, Object> guest = (Map<String, Object>) MAPPER.readValue(
				call(BLP, "false", null).body(), Map.class).get("footer");
		Map<String, Object> logged = (Map<String, Object>) MAPPER.readValue(
				call(BLP, "true", null).body(), Map.class).get("footer");

		assertThat((List<Map<String, Object>>) guest.get("menu_items"))
				.extracting(item -> item.get("label")).containsExactly("Ayuda");
		assertThat((List<Map<String, Object>>) logged.get("menu_items"))
				.extracting(item -> item.get("label")).containsExactly("Mis compras", "Ayuda");
	}

	@Test
	void favoriteStoreCookie_fillsSelectedStoreAnalytics_andMakesThePagePersonal() throws Exception {
		HttpResponse<String> response = call(BLP, "false", "7");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body())
				.contains("\"value\":\"Liverpool Santa Fe\"")
				.contains("\"value\":7");
		assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
		wireMock.verify(getRequestedFor(urlPathEqualTo("/brands/LP/stores/7")));
	}

	// ── failures ──────────────────────────────────────────────────────────────────────────────────

	@Test
	void clpWithoutAnAuthoredPage_returns404() throws Exception {
		HttpResponse<String> response = call("/clp/es-mx/tienda/belleza/cat5020010", "false", null);

		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("LANDING_PAGE_NOT_FOUND");
		wireMock.verify(getRequestedFor(urlPathEqualTo(PAGE_CLP_ENTRIES))
				.withQueryParam("query", containing("cat5020010")));
	}

	@Test
	void contentstackDown_returns502() throws Exception {
		wireMock.stubFor(get(urlPathEqualTo(PAGE_BLP_ENTRIES)).atPriority(1)
				.willReturn(aResponse().withStatus(500)));

		assertThat(call(BLP, "false", null).statusCode()).isEqualTo(502);
	}

	/**
	 * The page renders without categoryData when the indexer is down, never fails. A category no other
	 * test has asked for, because a successful answer is cached for a day and would hide the outage.
	 */
	@Test
	void indexerDown_servesThePageWithoutCategoryData() throws Exception {
		wireMock.stubFor(post(urlPathEqualTo("/search-category/LP/catst00000001")).atPriority(1)
				.willReturn(aResponse().withStatus(503)));

		HttpResponse<String> response = call("/blp/es-mx/tienda/otra/catst00000001", "false", null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).doesNotContain("\"categoryData\"");
		wireMock.verify(postRequestedFor(urlPathEqualTo("/search-category/LP/catst00000001")));
	}

	@Test
	void pageIsSelectedByCategoryId_withTheBrandsCredentials() throws Exception {
		call(BLP, "false", null);

		wireMock.verify(getRequestedFor(urlPathEqualTo(PAGE_BLP_ENTRIES))
				.withHeader("api_key", equalTo("api-key-lp"))
				.withQueryParam("query", equalTo("{\"category_id\":{\"$in\":[\"catst26617625\"]}}")));
	}

	// ── helpers ───────────────────────────────────────────────────────────────────────────────────

	@SuppressWarnings("unchecked")
	private static Map<String, Object> blockByUid(Map<String, Object> body, String uid) {
		return ((List<Map<String, Object>>) body.get("blocks")).stream()
				.filter(block -> uid.equals(block.get("uid")))
				.findFirst()
				.orElseThrow(() -> new AssertionError("block " + uid + " not in the response"));
	}

	private HttpResponse<String> call(String path, String loggedIn, String favoriteStore) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + path))
				.header("Authorization", TestServiceCredential.AUTHORIZATION_HEADER)
				.header("x-brand-id", "LP")
				.header("x-channel", "WEB")
				.header("x-is-logged-in", loggedIn)
				.timeout(Duration.ofSeconds(30))
				.GET();
		if (favoriteStore != null) {
			builder.header("Cookie", "favoriteStore=" + favoriteStore);
		}
		return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String fixture(String name) throws IOException {
		return Files.readString(Path.of("src/test/resources/fixtures/" + name), StandardCharsets.UTF_8);
	}

	private static ResponseDefinitionBuilder json(String body) {
		return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
	}
}
