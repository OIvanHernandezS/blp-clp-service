package com.liverpool.ms_clp_blp.clients.vertex;

import java.util.Base64;

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
import com.liverpool.dto.PlpRequestPayloadDTO;
import com.liverpool.ms_clp_blp.clients.vertex.dto.VertexSearchResponse;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import feign.FeignException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level test for {@link VertexClient} against a real socket.
 *
 * <p>This is the piece a mocked Feign interface cannot cover: it exercises the actual proxy Spring
 * builds — {@code ${vertex.search-path}} placeholder resolution in the {@code @PostMapping}, the
 * {@link VertexFeignConfig} transport and Basic-auth interceptor, and Jackson serialization of the
 * request and deserialization of the response. It is also the standing check that Spring Cloud
 * OpenFeign 5.0.2 behaves on Spring Boot 4.1.0, which is one minor ahead of the release train's
 * tested Boot version (see the {@code spring-cloud.version} comment in {@code pom.xml}): if that
 * pairing ever breaks, it breaks here first.</p>
 */
@ServiceCredential
@SpringBootTest(properties = {
		"management.server.port=0",
		"vertex.username=" + VertexClientWireTest.USER,
		"vertex.password=" + VertexClientWireTest.PASS
})
class VertexClientWireTest {

	static final String USER = "vertex-user";
	static final String PASS = "vertex-pass";

	private static final String SEARCH_PATH = "/v2/search";

	private static WireMockServer wireMock;

	@Autowired
	private VertexClient vertexClient;

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
	}

	@DynamicPropertySource
	static void vertexBaseUrl(DynamicPropertyRegistry registry) {
		registry.add("vertex.base-url", () -> "http://localhost:" + wireMock.port());
	}

	@Test
	void search_serializesRequest_sendsBasicAuth_andDeserializesResponse() {
		wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(SEARCH_PATH))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "meta": { "status": "SUCCESS" },
								  "data": { "records": [ { "productId": "sku-1", "title": "One" } ] }
								}
								""")));

		PlpRequestPayloadDTO request = new PlpRequestPayloadDTO();
		request.setCategoryId("cat-1");
		request.setPageSize(12);
		request.setBrand("LP");
		request.setChannel("WEB");

		VertexSearchResponse response = vertexClient.search(request);

		// Response side: the typed DTO came back through Jackson, proving the decoder is wired.
		assertThat(response).isNotNull();
		assertThat(response.data()).isNotNull();
		assertThat(response.data().records()).hasSize(1);
		assertThat(response.data().records().get(0).getProductId()).isEqualTo("sku-1");

		// Request side: body encoded as JSON and credentials applied by the client, not the call site.
		String expectedAuth = "Basic " + Base64.getEncoder()
				.encodeToString((USER + ":" + PASS).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		wireMock.verify(postRequestedFor(urlEqualTo(SEARCH_PATH))
				.withHeader("Authorization", equalTo(expectedAuth))
				.withRequestBody(equalToJson("""
						{ "categoryId": "cat-1", "pageSize": 12, "brand": "LP", "channel": "WEB" }
						""", true, true)));
	}

	/** Error statuses must surface as {@link FeignException} for the adapter to translate. */
	@Test
	void search_upstream500_throwsFeignException() {
		wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo(SEARCH_PATH))
				.willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> vertexClient.search(new PlpRequestPayloadDTO()))
				.isInstanceOf(FeignException.class);
	}
}
