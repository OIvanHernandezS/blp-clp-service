package com.liverpool.ms_clp_blp.clients.akamai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.akamai.edgegrid.signer.EdgeGridV1Signer;

import feign.RequestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the EdgeGrid {@code Authorization} header.
 *
 * <p>Akamai gives no useful feedback on a signing mistake — every one of them comes back as a 403
 * indistinguishable from a revoked credential — so the header's shape is pinned here rather than
 * discovered in production.</p>
 *
 * <p>The signer is subclassed to freeze its timestamp and nonce. Without that the signature is
 * different on every run by design, and the only thing assertable would be that the header is
 * non-empty.</p>
 */
class AkamaiEdgeGridInterceptorTest {

    private static final String HOST = "akab-test.luna.akamaiapis.net";
    private static final String CLIENT_TOKEN = "akab-client-token-xxxxxxxxxxxxxxxx";
    private static final String CLIENT_SECRET = "SuperSecretSigningKeyValue123456789=";
    private static final String ACCESS_TOKEN = "akab-access-token-xxxxxxxxxxxxxxxx";
    private static final String NONCE = "fixed-nonce-0000";
    private static final long TIMESTAMP = 1_500_000_000_000L;

    private static final AkamaiProperties CONFIGURED = new AkamaiProperties(
            HOST, "https://" + HOST, CLIENT_TOKEN, CLIENT_SECRET, ACCESS_TOKEN,
            Duration.ofSeconds(2), Duration.ofSeconds(10));

    @Test
    void apply_stampsAnEg1HmacSha256HeaderCarryingBothTokensATimestampAndANonce() {
        RequestTemplate template = purgeTemplate();

        interceptor(CONFIGURED).apply(template);

        String authorization = header(template);
        assertThat(authorization).startsWith("EG1-HMAC-SHA256 ");
        assertThat(authorization)
                .contains("client_token=" + CLIENT_TOKEN)
                .contains("access_token=" + ACCESS_TOKEN)
                .contains("nonce=" + NONCE)
                .contains("timestamp=")
                .contains("signature=");
    }

    /** The client secret is the HMAC key: it is used to sign, and must never appear in the header. */
    @Test
    void apply_neverPutsTheClientSecretInTheHeader() {
        RequestTemplate template = purgeTemplate();

        interceptor(CONFIGURED).apply(template);

        assertThat(header(template)).doesNotContain(CLIENT_SECRET);
    }

    @Test
    void apply_sameRequestAndSameClock_producesTheSameSignature() {
        RequestTemplate first = purgeTemplate();
        RequestTemplate second = purgeTemplate();

        interceptor(CONFIGURED).apply(first);
        interceptor(CONFIGURED).apply(second);

        assertThat(header(first)).isEqualTo(header(second));
    }

    /** The body is hashed into the signature, so changing it must change the signature. */
    @Test
    void apply_differentBody_producesADifferentSignature() {
        RequestTemplate home = purgeTemplate();
        RequestTemplate loyalty = templateWithBody("{\"objects\":[\"https://www.liverpool.com.mx/loyalty\"]}");

        interceptor(CONFIGURED).apply(home);
        interceptor(CONFIGURED).apply(loyalty);

        assertThat(header(home)).isNotEqualTo(header(loyalty));
    }

    /** The path is signed too — an invalidate signature must not be reusable for a delete. */
    @Test
    void apply_differentPath_producesADifferentSignature() {
        RequestTemplate invalidate = purgeTemplate();
        RequestTemplate delete = purgeTemplate();
        delete.uri("/ccu/v3/delete/url/production");

        interceptor(CONFIGURED).apply(invalidate);
        interceptor(CONFIGURED).apply(delete);

        assertThat(header(invalidate)).isNotEqualTo(header(delete));
    }

    @Test
    void apply_credentialMissing_failsWithoutNamingAnyValue() {
        AkamaiProperties blank = new AkamaiProperties("", "https://localhost", "", "", "",
                Duration.ofSeconds(2), Duration.ofSeconds(10));
        RequestTemplate template = purgeTemplate();

        assertThatThrownBy(() -> interceptor(blank).apply(template))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AKAMAI_CLIENT_SECRET")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .as("the message names the variables an operator must set, never their values")
                        .doesNotContain(CLIENT_SECRET));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static AkamaiEdgeGridInterceptor interceptor(AkamaiProperties properties) {
        return new AkamaiEdgeGridInterceptor(properties, frozenSigner());
    }

    /** Freezes the two values that make a real signature different on every call. */
    private static EdgeGridV1Signer frozenSigner() {
        return new EdgeGridV1Signer() {
            @Override
            protected long getTimestamp() {
                return TIMESTAMP;
            }

            @Override
            protected String getNonce() {
                return NONCE;
            }
        };
    }

    private static RequestTemplate purgeTemplate() {
        return templateWithBody("{\"objects\":[\"https://www.liverpool.com.mx/home\"]}");
    }

    private static RequestTemplate templateWithBody(String body) {
        RequestTemplate template = new RequestTemplate();
        template.method(feign.Request.HttpMethod.POST);
        template.uri("/ccu/v3/invalidate/url/production");
        template.header("Content-Type", "application/json");
        template.body(body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        return template;
    }

    private static String header(RequestTemplate template) {
        return template.headers().get("Authorization").iterator().next();
    }
}
