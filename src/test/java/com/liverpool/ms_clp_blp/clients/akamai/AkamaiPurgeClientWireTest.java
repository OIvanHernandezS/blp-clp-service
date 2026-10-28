package com.liverpool.ms_clp_blp.clients.akamai;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeRequest;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeResponse;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import feign.FeignException;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level test for {@link AkamaiPurgeClient} against a real socket.
 *
 * <p>This is the piece a mocked Feign interface cannot cover: the proxy Spring actually builds — the
 * {@code /ccu/v3/{action}/{objectType}/{network}} path variables, the {@link AkamaiFeignConfig}
 * transport, the EdgeGrid interceptor, and Jackson's handling of the request and response bodies.
 * The error taxonomy on top of it belongs to {@code AkamaiCachePurgeAdapterTest}.</p>
 *
 * <p><strong>No real Akamai call is possible here.</strong> {@code akamai.base-url} is repointed at a
 * WireMock server on an ephemeral local port, so the production edge is structurally unreachable and
 * the credentials below are fictitious.</p>
 *
 * <p>The last test is the standing guarantee that the EdgeGrid secrets never reach a log line. It is
 * asserted against a real call rather than a unit-level masker check, because that is the only way to
 * cover every line the outbound path actually emits.</p>
 */
@ServiceCredential
@SpringBootTest(properties = {
        "management.server.port=0",
        "logging.outbound.feign-enabled=true"
})
class AkamaiPurgeClientWireTest {

    static final String HOST = "akab-test.luna.akamaiapis.net";
    static final String CLIENT_TOKEN = "akab-client-token-wiretest";
    static final String CLIENT_SECRET = "WireTestSigningKeyValue0123456789=";
    static final String ACCESS_TOKEN = "akab-access-token-wiretest";

    private static final String INVALIDATE_URL_PATH = "/ccu/v3/invalidate/url/production";
    private static final String DELETE_TAG_PATH = "/ccu/v3/delete/tag/staging";

    private static final String HOME = "https://www.liverpool.com.mx/home";
    private static final String GLOBAL_DATA = "https://www.liverpool.com.mx/global-data";
    private static final String LOYALTY = "https://www.liverpool.com.mx/loyalty";

    private static final String ACCEPTED = """
            {
              "httpStatus": 201,
              "detail": "Request accepted",
              "purgeId": "e535071c-26b2-11e7-94d7-276f2f54d938",
              "supportId": "17PY1234567890123456-123456789",
              "estimatedSeconds": 5
            }
            """;

    private static WireMockServer wireMock;

    @Autowired
    private AkamaiPurgeClient akamaiPurgeClient;

    private ListAppender<ILoggingEvent> appender;
    private Logger outboundLogger;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @DynamicPropertySource
    static void akamaiEndpoint(DynamicPropertyRegistry registry) {
        registry.add("akamai.base-url", () -> "http://localhost:" + wireMock.port());
        registry.add("akamai.host", () -> HOST);
        registry.add("akamai.client-token", () -> CLIENT_TOKEN);
        registry.add("akamai.client-secret", () -> CLIENT_SECRET);
        registry.add("akamai.access-token", () -> ACCESS_TOKEN);
    }

    @BeforeEach
    void resetStubsAndCaptureLogs() {
        wireMock.resetAll();
        outboundLogger = (Logger) LoggerFactory.getLogger("com.liverpool.ms_clp_blp.clients.feign.FeignOutboundLogger");
        appender = new ListAppender<>();
        appender.start();
        outboundLogger.addAppender(appender);
        outboundLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        outboundLogger.detachAppender(appender);
        outboundLogger.setLevel(null);
    }

    @Test
    void purge_theThreeEndpoints_postsThemToTheInvalidateUrlEndpointAndParsesTheAcceptance() {
        wireMock.stubFor(post(urlEqualTo(INVALIDATE_URL_PATH))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ACCEPTED)));

        AkamaiPurgeResponse response = akamaiPurgeClient.purge("invalidate", "url", "production",
                new AkamaiPurgeRequest(List.of(HOME, GLOBAL_DATA, LOYALTY)));

        assertThat(response.httpStatus()).isEqualTo(201);
        assertThat(response.purgeId()).isEqualTo("e535071c-26b2-11e7-94d7-276f2f54d938");
        assertThat(response.supportId()).isEqualTo("17PY1234567890123456-123456789");
        assertThat(response.estimatedSeconds()).isEqualTo(5);

        wireMock.verify(postRequestedFor(urlEqualTo(INVALIDATE_URL_PATH))
                .withRequestBody(equalToJson("""
                        {"objects": ["%s", "%s", "%s"]}
                        """.formatted(HOME, GLOBAL_DATA, LOYALTY))));
    }

    @Test
    void purge_msHomeTag_postsTheBareTagToTheTagEndpoint() {
        wireMock.stubFor(post(urlEqualTo(DELETE_TAG_PATH))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(ACCEPTED)));

        akamaiPurgeClient.purge("delete", "tag", "staging",
                new AkamaiPurgeRequest(List.of("ms-clp-blp")));

        wireMock.verify(postRequestedFor(urlEqualTo(DELETE_TAG_PATH))
                .withRequestBody(equalToJson("{\"objects\": [\"ms-clp-blp\"]}")));
    }

    @Test
    void purge_signsEveryRequestWithAnEdgeGridAuthorizationHeader() {
        wireMock.stubFor(post(urlEqualTo(INVALIDATE_URL_PATH))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody(ACCEPTED)));

        akamaiPurgeClient.purge("invalidate", "url", "production",
                new AkamaiPurgeRequest(List.of(HOME)));

        LoggedRequest sent = wireMock.findAll(postRequestedFor(urlEqualTo(INVALIDATE_URL_PATH))).get(0);
        String authorization = sent.getHeader("Authorization");
        assertThat(authorization)
                .as("Fast Purge has no basic-auth mode; only an EdgeGrid signature is accepted")
                .startsWith("EG1-HMAC-SHA256 ")
                .contains("client_token=" + CLIENT_TOKEN)
                .contains("access_token=" + ACCESS_TOKEN)
                .contains("nonce=")
                .contains("timestamp=")
                .contains("signature=");
        assertThat(authorization)
                .as("the signing key is used to sign, never transmitted")
                .doesNotContain(CLIENT_SECRET);
    }

    @Test
    void purge_upstream500_surfacesAsFeignExceptionForTheAdapterToTranslate() {
        wireMock.stubFor(post(urlEqualTo(INVALIDATE_URL_PATH))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> akamaiPurgeClient.purge("invalidate", "url", "production",
                new AkamaiPurgeRequest(List.of(HOME))))
                .isInstanceOf(FeignException.class);
    }

    @Test
    void purge_upstream403_surfacesAsFeignExceptionRatherThanBeingSwallowed() {
        wireMock.stubFor(post(urlEqualTo(INVALIDATE_URL_PATH))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"detail\":\"forbidden\"}")));

        assertThatThrownBy(() -> akamaiPurgeClient.purge("invalidate", "url", "production",
                new AkamaiPurgeRequest(List.of(HOME))))
                .isInstanceOf(FeignException.class)
                .satisfies(thrown -> assertThat(((FeignException) thrown).status()).isEqualTo(403));
    }

    /**
     * The outbound logger renders every call as a runnable curl, bodies included. That line is the
     * one place an EdgeGrid credential could plausibly leak, so it is asserted directly.
     */
    @Test
    void noLogLineEverCarriesAnEdgeGridSecret() {
        wireMock.stubFor(post(urlEqualTo(INVALIDATE_URL_PATH))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json").withBody(ACCEPTED)));

        akamaiPurgeClient.purge("invalidate", "url", "production",
                new AkamaiPurgeRequest(List.of(HOME)));

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(CLIENT_SECRET)
                .doesNotContain(CLIENT_TOKEN)
                .doesNotContain(ACCESS_TOKEN)
                .doesNotContain("EG1-HMAC-SHA256"));
        assertThat(appender.list)
                .as("the header is named but its value is masked, so a missing credential is still diagnosable")
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("Authorization: ***"));
    }
}
