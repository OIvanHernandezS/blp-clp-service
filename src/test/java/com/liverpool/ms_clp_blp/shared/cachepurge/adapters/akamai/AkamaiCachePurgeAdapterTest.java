package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.akamai;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.liverpool.ms_clp_blp.clients.akamai.AkamaiProperties;
import com.liverpool.ms_clp_blp.clients.akamai.AkamaiPurgeClient;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeRequest;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeResponse;
import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeFailedException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeRequest;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeTargetKind;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

import feign.FeignException;
import feign.Request;
import feign.RetryableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the Akamai purge adapter.
 *
 * <p>The {@link AkamaiPurgeClient} Feign interface is mocked, so these stay fast and never open a
 * socket. The real HTTP contract — path resolution, EdgeGrid signing, Jackson wiring — is covered
 * separately by {@code AkamaiPurgeClientWireTest}.</p>
 *
 * <p>The error cases are the reason this class is long. Every branch an operator can hit during an
 * incident is pinned here, because the one thing worse than a purge failing is a purge that reports
 * success while the edge keeps serving stale content.</p>
 */
class AkamaiCachePurgeAdapterTest {

    private static final String HOME = "https://www.liverpool.com.mx/home";
    private static final String GLOBAL_DATA = "https://www.liverpool.com.mx/global-data";
    private static final String LOYALTY = "https://www.liverpool.com.mx/loyalty";
    private static final String TAG = "ms-clp-blp";

    private static final AkamaiProperties CONFIGURED = new AkamaiProperties(
            "akab-test.luna.akamaiapis.net", "https://akab-test.luna.akamaiapis.net",
            "akab-client-token", "client-secret", "akab-access-token",
            Duration.ofSeconds(2), Duration.ofSeconds(10));

    private AkamaiPurgeClient akamaiPurgeClient;
    private AkamaiCachePurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        akamaiPurgeClient = mock(AkamaiPurgeClient.class);
        adapter = new AkamaiCachePurgeAdapter(akamaiPurgeClient, CONFIGURED);
    }

    // ── the three endpoints, purged by URL ────────────────────────────────────────────────────────

    @Test
    void purge_theThreeEndpointPaths_sendsThemAsAbsoluteUrlsToTheUrlEndpoint() {
        stubAccepted();

        PurgeOutcome outcome = adapter.purge(new PurgeRequest(PurgeTargetKind.URL, PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, List.of(HOME, GLOBAL_DATA, LOYALTY)));

        verify(akamaiPurgeClient).purge(eq("invalidate"), eq("url"), eq("production"), any());
        assertThat(capturedObjects())
                .as("Fast Purge matches absolute URLs, never bare paths")
                .containsExactly(HOME, GLOBAL_DATA, LOYALTY);
        assertThat(outcome.objectCount()).isEqualTo(3);
        assertThat(outcome.purgeId()).isEqualTo("purge-1");
        assertThat(outcome.supportId()).isEqualTo("support-1");
        assertThat(outcome.estimatedSeconds()).isEqualTo(5);
    }

    @Test
    void purge_msHomeTag_usesTheTagEndpointAndSendsTheBareTagName() {
        stubAccepted();

        PurgeOutcome outcome = adapter.purge(new PurgeRequest(PurgeTargetKind.TAG, PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, List.of(TAG)));

        verify(akamaiPurgeClient).purge(eq("invalidate"), eq("tag"), eq("production"), any());
        assertThat(capturedObjects())
                .as("a cache tag is sent as its bare name, never as a URL")
                .containsExactly(TAG);
        assertThat(outcome.kind()).isEqualTo(PurgeTargetKind.TAG);
    }

    @Test
    void purge_deleteOnStaging_putsBothOnThePathWhereAkamaiExpectsThem() {
        stubAccepted();

        adapter.purge(new PurgeRequest(PurgeTargetKind.URL, PurgeAction.DELETE, PurgeNetwork.STAGING,
                List.of(HOME)));

        verify(akamaiPurgeClient).purge(eq("delete"), eq("url"), eq("staging"), any());
    }

    // ── error handling ────────────────────────────────────────────────────────────────────────────

    @Test
    void purge_upstream400_throwsCachePurgeFailedAsBadGateway() {
        stubFailure(feignError(400, "{\"detail\":\"invalid hostname\"}"));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .satisfies(thrown -> {
                    CachePurgeFailedException failure = (CachePurgeFailedException) thrown;
                    assertThat(failure.getStatus()).isEqualTo(502);
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCodes.CACHE_PURGE_FAILED);
                    assertThat(failure.getDetail()).contains("400", "invalid hostname");
                });
    }

    @Test
    void purge_upstream403_saysTheCredentialWasRejectedRatherThanBlamingTheEdge() {
        stubFailure(feignError(403, "{\"detail\":\"forbidden\"}"));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .hasMessageContaining("credential")
                .satisfies(thrown -> assertThat(((CachePurgeFailedException) thrown).getDetail())
                        .as("403 is the one status an operator can act on, so it names the likely causes")
                        .contains("expired", "scope"));
    }

    @Test
    void purge_upstream429_isReportedAsRateLimitingAndNeverRetried() {
        stubFailure(feignError(429, "{\"detail\":\"too many requests\"}"));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .hasMessageContaining("rate limiting");

        verify(akamaiPurgeClient).purge(any(), any(), any(), any());
    }

    @Test
    void purge_upstream500_throwsCachePurgeFailedAndKeepsTheStatusInContext() {
        stubFailure(feignError(500, "boom"));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .satisfies(thrown -> {
                    CachePurgeFailedException failure = (CachePurgeFailedException) thrown;
                    assertThat(failure.getStatus()).isEqualTo(502);
                    assertThat(failure.isRetryable()).isTrue();
                    assertThat(failure.context()).containsEntry("upstreamStatus", 500);
                });
    }

    /**
     * {@code RetryableException} does not extend the status-carrying {@code FeignException} types, so
     * an adapter catching only {@code FeignException} lets every connect timeout escape untyped.
     */
    @Test
    void purge_connectTimeout_throwsCachePurgeFailedAsGatewayTimeout() {
        stubFailure(new RetryableException(-1, "connect timed out", Request.HttpMethod.POST, (Long) null,
                dummyRequest()));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .satisfies(thrown -> {
                    CachePurgeFailedException failure = (CachePurgeFailedException) thrown;
                    assertThat(failure.getStatus()).isEqualTo(504);
                    assertThat(failure.getCategory()).isEqualTo(ErrorCategory.TIMEOUT);
                    assertThat(failure.getDetail()).contains("connect timed out");
                });
    }

    @Test
    void purge_2xxWithoutAPurgeId_isTreatedAsAFailureBecauseTheresNothingToTrack() {
        when(akamaiPurgeClient.purge(any(), any(), any(), any()))
                .thenReturn(new AkamaiPurgeResponse(201, "Request accepted", null, "support-1", 5, null));

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class)
                .hasMessageContaining("did not identify it");
    }

    @Test
    void purge_nullBody_isTreatedAsAFailureRatherThanNpeing() {
        when(akamaiPurgeClient.purge(any(), any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> adapter.purge(urlPurge()))
                .isInstanceOf(CachePurgeFailedException.class);
    }

    // ── configuration gate ────────────────────────────────────────────────────────────────────────

    @Test
    void configured_blankCredential_isFalseSoTheUseCaseCanRefuseBeforeAnyCall() {
        AkamaiProperties blank = new AkamaiProperties("", "https://localhost", "", "", "",
                Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(new AkamaiCachePurgeAdapter(akamaiPurgeClient, blank).configured()).isFalse();
        assertThat(adapter.configured()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static PurgeRequest urlPurge() {
        return new PurgeRequest(PurgeTargetKind.URL, PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION,
                List.of(HOME));
    }

    private void stubAccepted() {
        when(akamaiPurgeClient.purge(any(), any(), any(), any())).thenReturn(
                new AkamaiPurgeResponse(201, "Request accepted", "purge-1", "support-1", 5, null));
    }

    // doThrow(...).when(...) rather than when(...).thenThrow(...): the latter *calls* the mock.
    private void stubFailure(Exception failure) {
        doThrow(failure).when(akamaiPurgeClient).purge(any(), any(), any(), any());
    }

    private List<String> capturedObjects() {
        ArgumentCaptor<AkamaiPurgeRequest> captor = ArgumentCaptor.forClass(AkamaiPurgeRequest.class);
        verify(akamaiPurgeClient).purge(any(), any(), any(), captor.capture());
        return captor.getValue().objects();
    }

    private static FeignException feignError(int status, String body) {
        return FeignException.errorStatus(
                "AkamaiPurgeClient#purge(String,String,String,AkamaiPurgeRequest)",
                feign.Response.builder()
                        .status(status)
                        .reason("error")
                        .request(dummyRequest())
                        .headers(Map.of())
                        .body(body, StandardCharsets.UTF_8)
                        .build());
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST,
                "https://akab-test.luna.akamaiapis.net/ccu/v3/invalidate/url/production",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
