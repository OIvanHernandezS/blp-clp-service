package com.liverpool.ms_clp_blp.clients.salesforce;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.liverpool.dto.salesforce.SalesforceFlags;
import com.liverpool.dto.salesforce.SalesforceRequest;
import com.liverpool.dto.salesforce.SalesforceResponse;
import com.liverpool.dto.salesforce.SalesforceSource;
import com.liverpool.dto.salesforce.SalesforceUser;
import com.liverpool.dto.salesforce.SalesforceUserAttributes;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import feign.FeignException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level test for {@link SalesforceCarrouselsClient} against a real socket.
 *
 * <p>This is the standing proof that the {@code platform-commons} reuse actually wires up — the part no
 * mocked Feign interface can cover:</p>
 * <ul>
 *   <li>commons' {@code SalesforceCarrouselsFeignConfig} is picked up as the per-client configuration,
 *       and its interceptor stamps {@code Authorization: Basic base64(user:pass)} from
 *       {@code salesforce.auth.*} — so no ms-clp-blp call site handles Salesforce credentials;</li>
 *   <li>{@code ${api.salesforce-carrousels.url}} resolves, and the client posts to it directly (the
 *       method declares no path, unlike every other ms-clp-blp client);</li>
 *   <li>commons' Jackson 2 annotated DTOs still bind under ms-clp-blp's Jackson 3 — including
 *       {@code ID_ATG1}, which survives only because the field name is the wire name.</li>
 * </ul>
 */
@ServiceCredential
@SpringBootTest(properties = {
		"management.server.port=0",
		"salesforce.auth.user=" + SalesforceCarrouselsClientWireTest.USER,
		"salesforce.auth.pass=" + SalesforceCarrouselsClientWireTest.PASS
})
class SalesforceCarrouselsClientWireTest {

	static final String USER = "sf-user";
	static final String PASS = "sf-pass";

	private static final String ACTIONS_PATH = "/api2/authevent/liverpool";

	private static WireMockServer wireMock;

	@Autowired
	private SalesforceCarrouselsClient salesforceCarrouselsClient;

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

	/** The carousels url is the FULL endpoint, not a base url — the client declares no path of its own. */
	@DynamicPropertySource
	static void carrouselsUrl(DynamicPropertyRegistry registry) {
		registry.add("api.salesforce-carrousels.url",
				() -> "http://localhost:" + wireMock.port() + ACTIONS_PATH);
	}

	@Test
	void post_serializesRequest_sendsBasicAuth_andDeserializesResponse() {
		wireMock.stubFor(post(urlEqualTo(ACTIONS_PATH))
				.willReturn(aResponse()
						.withStatus(200)
						.withHeader("Content-Type", "application/json")
						.withBody("""
								{
								  "campaignResponses": [{
								    "campaignName": "CMSTePuedeGustar",
								    "payload": {
								      "title": "Te puede gustar",
								      "productDataMapped": [{ "productId": "sku-1", "title": "One" }]
								    }
								  }]
								}
								""")));

		ResponseEntity<SalesforceResponse> response =
				salesforceCarrouselsClient.post(SalesforceRequest.builder()
						.action("CMSTePuedeGustar")
						.flags(SalesforceFlags.builder().noCampaigns(false).build())
						.source(SalesforceSource.builder()
								.channel("Server").application("Web").url("cat-4020").build())
						.user(SalesforceUser.builder()
								.attributes(SalesforceUserAttributes.builder()
										.ID_ATG1("user-1").build())
								.build())
						.build());

		// Response side: commons' DTOs came back through ms-clp-blp's Jackson, decoder wired.
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getCampaignResponses()).hasSize(1);
		assertThat(response.getBody().getCampaignResponses().get(0).getPayload().getTitle())
				.isEqualTo("Te puede gustar");
		assertThat(response.getBody().getCampaignResponses().get(0).getPayload()
				.getProductDataMapped().get(0).getProductId()).isEqualTo("sku-1");

		// Request side: credentials applied by commons' interceptor, and the envelope serialized with
		// the exact field names Evergage expects — ID_ATG1 included, and no nulls emitted.
		String expectedAuth = "Basic "
				+ Base64.getEncoder().encodeToString((USER + ":" + PASS).getBytes(StandardCharsets.UTF_8));
		wireMock.verify(postRequestedFor(urlEqualTo(ACTIONS_PATH))
				.withHeader("Authorization", equalTo(expectedAuth))
				.withRequestBody(equalToJson("""
						{
						  "action": "CMSTePuedeGustar",
						  "flags":  { "noCampaigns": false },
						  "source": { "channel": "Server", "application": "Web", "url": "cat-4020" },
						  "user":   { "attributes": { "ID_ATG1": "user-1" } }
						}
						""", true, true)));
	}

	/** Error statuses must surface as {@link FeignException} for the adapter to translate. */
	@Test
	void post_upstream500_throwsFeignException() {
		wireMock.stubFor(post(urlEqualTo(ACTIONS_PATH)).willReturn(aResponse().withStatus(500)));

		assertThatThrownBy(() -> salesforceCarrouselsClient.post(
				SalesforceRequest.builder().action("CMSTePuedeGustar").build()))
				.isInstanceOf(FeignException.class);
	}
}
