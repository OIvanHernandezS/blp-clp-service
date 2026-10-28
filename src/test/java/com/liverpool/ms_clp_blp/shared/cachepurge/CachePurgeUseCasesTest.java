package com.liverpool.ms_clp_blp.shared.cachepurge;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeNotConfiguredException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeRequest;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeTargetKind;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgePort;
import com.liverpool.ms_clp_blp.shared.errors.ValidationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for what a purge run actually submits.
 *
 * <p>The interesting contract is the splitting: Akamai separates URL and cache-tag purges into
 * different endpoints, so "purge ms-clp-blp" is necessarily two calls and neither may carry the other's
 * objects. The CDN itself is behind {@link CachePurgePort} and is mocked.</p>
 *
 * <p>For operator-named objects the second contract is the fence: only allowed hosts and configured
 * tags, all validated before anything leaves the process.</p>
 */
class CachePurgeUseCasesTest {

    private static final String SITE = "https://ms-crp-home-service.liverpool.com.mx";
    private static final List<String> PATHS = List.of("/home", "/global-data", "/loyalty");
    private static final List<String> TAGS = List.of("ms-clp-blp");

    private CachePurgePort cachePurgePort;

    @BeforeEach
    void setUp() {
        cachePurgePort = mock(CachePurgePort.class);
        when(cachePurgePort.configured()).thenReturn(true);
        when(cachePurgePort.purge(any())).thenAnswer(invocation -> {
            PurgeRequest request = invocation.getArgument(0);
            return new PurgeOutcome(request.kind(), request.action(), request.network(),
                    request.objects(), "purge-1", "support-1", 5);
        });
    }

    @Test
    void purgeAll_submitsTheThreeEndpointsAsUrlsAndTheTagSeparately() {
        PurgeReport report = useCases(enabled()).purgeAll(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);

        List<PurgeRequest> submitted = captureRequests(2);
        assertThat(submitted.get(0).kind()).isEqualTo(PurgeTargetKind.URL);
        assertThat(submitted.get(0).objects()).containsExactly(
                SITE + "/home", SITE + "/global-data", SITE + "/loyalty");
        assertThat(submitted.get(1).kind()).isEqualTo(PurgeTargetKind.TAG);
        assertThat(submitted.get(1).objects())
                .as("a cache tag must never be sent to the URL endpoint")
                .containsExactly("ms-clp-blp");
        assertThat(report.totalObjects()).isEqualTo(4);
    }

    @Test
    void purgePaths_submitsOnlyTheUrlGroup() {
        useCases(enabled()).purgePaths(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);

        assertThat(captureRequests(1).get(0).kind()).isEqualTo(PurgeTargetKind.URL);
    }

    @Test
    void purgeTags_submitsOnlyTheTagGroup() {
        useCases(enabled()).purgeTags(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);

        assertThat(captureRequests(1).get(0).kind()).isEqualTo(PurgeTargetKind.TAG);
    }

    @Test
    void purgeAll_passesTheOperatorsActionAndNetworkStraightThrough() {
        useCases(enabled()).purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING);

        assertThat(captureRequests(2)).allSatisfy(request -> {
            assertThat(request.action()).isEqualTo(PurgeAction.DELETE);
            assertThat(request.network()).isEqualTo(PurgeNetwork.STAGING);
        });
    }

    /** An unconfigured environment must cost zero requests, not one 403 per group. */
    @Test
    void purgeAll_credentialMissing_throwsBeforeAnythingLeavesTheProcess() {
        when(cachePurgePort.configured()).thenReturn(false);

        assertThatThrownBy(() -> useCases(enabled()).purgeAll(PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION))
                .isInstanceOf(CachePurgeNotConfiguredException.class)
                .satisfies(thrown -> assertThat(((CachePurgeNotConfiguredException) thrown).getDetail())
                        .as("the detail names the variables, never their values")
                        .contains("AKAMAI_CLIENT_SECRET")
                        .doesNotContain("client-secret"));

        verify(cachePurgePort, times(0)).purge(any());
    }

    /** Akamai answers a purge with no objects with a 400, which reads as a broken integration. */
    @Test
    void purgeTags_nothingConfigured_submitsNothingRatherThanAnEmptyPurge() {
        CachePurgeProperties noTags = new CachePurgeProperties(SITE, PATHS, List.of(), List.of(),
                PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, true);

        PurgeReport report = useCases(noTags).purgeTags(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);

        assertThat(report.outcomes()).isEmpty();
        verify(cachePurgePort).configured();
        verify(cachePurgePort, times(0)).purge(any());
    }

    /** A rejection must propagate: a half-purged edge reported as success is the worst outcome. */
    @Test
    void purgeAll_firstGroupFails_doesNotSubmitTheSecond() {
        // doThrow(...).when(...) rather than when(...).thenThrow(...): the latter *calls* the mock,
        // which runs the answer stubbed in setUp with a null argument.
        doThrow(new IllegalStateException("rejected")).when(cachePurgePort).purge(any());

        assertThatThrownBy(() -> useCases(enabled()).purgeAll(PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION))
                .isInstanceOf(IllegalStateException.class);

        verify(cachePurgePort, times(1)).purge(any());
    }

    @Test
    void purgeAll_portNeverConsulted_whenNothingIsConfiguredToPurgeAtAll() {
        CachePurgePort untouched = mock(CachePurgePort.class);
        when(untouched.configured()).thenReturn(true);
        CachePurgeProperties empty = new CachePurgeProperties("", List.of(), List.of(), List.of(),
                PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, true);

        PurgeReport report = new CachePurgeUseCases(untouched, empty)
                .purgeAll(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);

        assertThat(report.outcomes()).isEmpty();
        assertThat(report.totalObjects()).isZero();
    }

    /** Guards the "no purge without a credential check" invariant for a port that is never used. */
    @Test
    void purgeAll_disabledPortIsNeverCalledForAnything_whenNotConfigured() {
        CachePurgePort unconfigured = mock(CachePurgePort.class);
        when(unconfigured.configured()).thenReturn(false);
        CachePurgeUseCases useCases = new CachePurgeUseCases(unconfigured, enabled());

        assertThatThrownBy(() -> useCases.purgePaths(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION))
                .isInstanceOf(CachePurgeNotConfiguredException.class);

        verify(unconfigured).configured();
        verifyNoInteractions(cachePurgePort);
    }

    // ── purgeObjects: what the operator names ─────────────────────────────────────────────────────

    @Test
    void purgeObjects_relativePathsAreJoinedOntoTheSiteAndDuplicatesSentOnce() {
        useCases(enabled()).purgeObjects(PurgeAction.INVALIDATE, PurgeNetwork.STAGING,
                List.of("/global-data", SITE + "/home", "home"), null);

        PurgeRequest submitted = captureRequests(1).get(0);
        assertThat(submitted.kind()).isEqualTo(PurgeTargetKind.URL);
        assertThat(submitted.objects()).containsExactly(SITE + "/global-data", SITE + "/home");
        assertThat(submitted.network()).isEqualTo(PurgeNetwork.STAGING);
    }

    @Test
    void purgeObjects_urlsAndTags_areTwoCallsUrlsFirstWithTheOperatorsOptions() {
        PurgeReport report = useCases(enabled()).purgeObjects(PurgeAction.DELETE, PurgeNetwork.STAGING,
                List.of(SITE + "/home"), List.of(" ms-clp-blp "));

        List<PurgeRequest> submitted = captureRequests(2);
        assertThat(submitted.get(0).kind()).isEqualTo(PurgeTargetKind.URL);
        assertThat(submitted.get(0).objects()).containsExactly(SITE + "/home");
        assertThat(submitted.get(1).kind()).isEqualTo(PurgeTargetKind.TAG);
        assertThat(submitted.get(1).objects()).containsExactly("ms-clp-blp");
        assertThat(submitted).allSatisfy(request -> {
            assertThat(request.action()).isEqualTo(PurgeAction.DELETE);
            assertThat(request.network()).isEqualTo(PurgeNetwork.STAGING);
        });
        assertThat(report.totalObjects()).isEqualTo(2);
    }

    @Test
    void purgeObjects_tagsOnly_submitsOnlyTheTagGroup() {
        useCases(enabled()).purgeObjects(PurgeAction.INVALIDATE, PurgeNetwork.STAGING, List.of(), TAGS);

        assertThat(captureRequests(1).get(0).kind()).isEqualTo(PurgeTargetKind.TAG);
    }

    /** The credential usually reaches every property of the account; this endpoint must not. */
    @Test
    void purgeObjects_hostOutsideTheAllowList_is400AndNothingIsSent() {
        assertRejected(List.of(SITE + "/home", "https://www.liverpool.com.mx/home"), null,
                "www.liverpool.com.mx");
    }

    @Test
    void purgeObjects_configuredAllowList_isWhatDecides() {
        CachePurgeProperties www = new CachePurgeProperties(SITE, PATHS, TAGS,
                List.of("www.liverpool.com.mx"), PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, true);

        useCases(www).purgeObjects(PurgeAction.INVALIDATE, PurgeNetwork.STAGING,
                List.of("https://www.liverpool.com.mx/home"), null);

        assertThat(captureRequests(1).get(0).objects()).containsExactly("https://www.liverpool.com.mx/home");
    }

    /** A cache tag spans every property that emits it, so only the configured ones may be named. */
    @Test
    void purgeObjects_unconfiguredTag_is400AndNothingIsSent() {
        assertRejected(null, List.of("ms-clp-blp", "other-team"), "other-team");
    }

    @Test
    void purgeObjects_malformedOrNonHttpUrl_is400() {
        for (String url : List.of("ftp://ms-crp-home-service.liverpool.com.mx/home", "https://",
                "https://bad host/home")) {
            assertRejected(List.of(url), null, "http(s)");
        }
    }

    @Test
    void purgeObjects_blankEntry_is400() {
        assertRejected(Arrays.asList(SITE + "/home", " "), null, "blank");
        assertRejected(null, Arrays.asList("ms-clp-blp", null), "blank");
    }

    @Test
    void purgeObjects_nothingNamed_is400() {
        assertRejected(null, null, "urls");
        assertRejected(List.of(), List.of(), "urls");
    }

    @Test
    void purgeObjects_moreThanTheCap_is400() {
        List<String> tooMany = IntStream.rangeClosed(0, CachePurgeUseCases.MAX_OBJECTS)
                .mapToObj(i -> SITE + "/p" + i)
                .toList();

        assertRejected(tooMany, null, String.valueOf(CachePurgeUseCases.MAX_OBJECTS));
    }

    @Test
    void purgeObjects_relativePathWithoutASite_is400() {
        CachePurgeProperties noSite = new CachePurgeProperties("", PATHS, TAGS,
                List.of("ms-crp-home-service.liverpool.com.mx"), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, true);

        assertThatThrownBy(() -> useCases(noSite).purgeObjects(PurgeAction.INVALIDATE,
                PurgeNetwork.STAGING, List.of("/home"), null))
                .isInstanceOf(ValidationException.class);
        verify(cachePurgePort, never()).purge(any());
    }

    @Test
    void purgeObjects_credentialMissing_throwsNotConfiguredWithoutSending() {
        when(cachePurgePort.configured()).thenReturn(false);

        assertThatThrownBy(() -> useCases(enabled()).purgeObjects(PurgeAction.INVALIDATE,
                PurgeNetwork.STAGING, List.of(SITE + "/home"), null))
                .isInstanceOf(CachePurgeNotConfiguredException.class);
        verify(cachePurgePort, never()).purge(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void assertRejected(List<String> urls, List<String> tags, String detailContains) {
        assertThatThrownBy(() -> useCases(enabled()).purgeObjects(PurgeAction.INVALIDATE,
                PurgeNetwork.STAGING, urls, tags))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).getDetail())
                        .containsIgnoringCase(detailContains));
        verify(cachePurgePort, never()).purge(any());
    }

    private CachePurgeUseCases useCases(CachePurgeProperties properties) {
        return new CachePurgeUseCases(cachePurgePort, properties);
    }

    private static CachePurgeProperties enabled() {
        return new CachePurgeProperties(SITE, PATHS, TAGS, List.of(), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, true);
    }

    private List<PurgeRequest> captureRequests(int expected) {
        ArgumentCaptor<PurgeRequest> captor = ArgumentCaptor.forClass(PurgeRequest.class);
        verify(cachePurgePort, times(expected)).purge(captor.capture());
        return captor.getAllValues();
    }
}
