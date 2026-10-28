package com.liverpool.ms_clp_blp.shared.cmspurgewebhook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.ms_clp_blp.shared.cachepurge.CachePurgeProperties;
import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeFailedException;
import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeNotConfiguredException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeTargetKind;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgeService;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What a Contentstack delivery does, and — more often — does not do.
 *
 * <p>Two properties are asserted repeatedly on purpose, because both are load-bearing and neither is
 * visible in a response status: an irrelevant or failed delivery must never reach Akamai, and no path
 * through this class may throw. The webhook answers 200 to everything, so an escaping exception would
 * become a 500 and Contentstack would retry a delivery that cannot succeed.</p>
 */
@ExtendWith(MockitoExtension.class)
class CmsPurgeWebhookUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00Z");

    @Mock
    private CachePurgeService cachePurgeService;

    /** A publish of the Fashion Fest template, shaped as Contentstack delivers it. */
    private static Map<String, Object> delivery(String contentTypeUid, String event) {
        return Map.of(
                "event", event,
                "data", Map.of(
                        "content_type", Map.of("uid", contentTypeUid),
                        "entry", Map.of(
                                "uid", "blt2f716c7fdc5e71be",
                                "title", "Template - AO Fashion Fest | 070926",
                                "locale", "es-mx")));
    }

    private static CmsPurgeWebhookProperties trigger(boolean enabled, Duration cooldown) {
        return new CmsPurgeWebhookProperties(List.of("page_blp", "page_clp"), PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, cooldown, enabled);
    }

    private static CachePurgeProperties purgeScope(boolean enabled) {
        return new CachePurgeProperties("https://ms-crp-home-service.liverpool.com.mx",
                List.of("/home", "/global-data", "/loyalty"), List.of("ms-clp-blp"), List.of(),
                PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, enabled);
    }

    private static PurgeReport report(int objects) {
        return new PurgeReport(List.of(new PurgeOutcome(
                PurgeTargetKind.URL, PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION,
                List.of("https://ms-crp-home-service.liverpool.com.mx/home",
                        "https://ms-crp-home-service.liverpool.com.mx/global-data",
                        "https://ms-crp-home-service.liverpool.com.mx/loyalty").subList(0, objects),
                "purge-1", "support-1", 5)));
    }

    private CmsPurgeWebhookUseCases useCases(CmsPurgeWebhookProperties trigger,
                                             CachePurgeProperties scope) {
        return new CmsPurgeWebhookUseCases(cachePurgeService, trigger, scope,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CmsPurgeWebhookUseCases enabledUseCases() {
        return useCases(trigger(true, Duration.ZERO), purgeScope(true));
    }

    /** The two content types the feature exists for, taken straight from the CMS screens. */
    @ParameterizedTest
    @ValueSource(strings = {"page_blp", "page_clp"})
    void aPublishOfAConfiguredContentTypePurgesPathsAndTags(String contentType) {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());

        CmsPurgeWebhookResult result = enabledUseCases().apply(delivery(contentType, "publish"));

        verify(cachePurgeService).purgeAll(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);
        assertThat(result.purged()).isTrue();
        assertThat(result.contentType()).isEqualTo(contentType);
        assertThat(result.event()).isEqualTo("publish");
        assertThat(result.objectCount()).isEqualTo(3);
        assertThat(result.action()).isEqualTo(PurgeAction.INVALIDATE);
        assertThat(result.network()).isEqualTo(PurgeNetwork.PRODUCTION);
    }

    /**
     * The event is not branched on: an unpublish or a delete leaves the edge serving content that no
     * longer exists, which is the more visible of the two failures.
     */
    @ParameterizedTest
    @ValueSource(strings = {"publish", "unpublish", "delete", "entry.publish"})
    void everyEventPurges(String event) {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());

        assertThat(enabledUseCases().apply(delivery("page_blp", event)).purged()).isTrue();
    }

    @Test
    void readsTheContentTypeFromTheTopLevelPositionToo() {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());

        CmsPurgeWebhookResult result = enabledUseCases()
                .apply(Map.of("event", "publish", "content_type_uid", "page_blp"));

        assertThat(result.purged()).isTrue();
    }

    @Test
    void matchesTheConfiguredContentTypeIgnoringCase() {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());

        assertThat(enabledUseCases().apply(delivery("PAGE_BLP", "publish")).purged()).isTrue();
    }

    @Test
    void ignoresAContentTypeThatFrontsNothingCached() {
        CmsPurgeWebhookResult result = enabledUseCases().apply(delivery("ms_clp_blp_flags", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).contains("ms_clp_blp_flags").contains("page_blp", "page_clp");
        assertThat(result.action()).isNull();
        assertThat(result.network()).isNull();
        verifyNoInteractions(cachePurgeService);
    }

    @Test
    void ignoresADeliveryThatNamesNoContentType() {
        CmsPurgeWebhookResult result = enabledUseCases().apply(Map.of("event", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).isEqualTo("Delivery carried no content type");
        verifyNoInteractions(cachePurgeService);
    }

    @Test
    void ignoresAnEmptyDelivery() {
        assertThat(enabledUseCases().apply(Map.of()).purged()).isFalse();
        verifyNoInteractions(cachePurgeService);
    }

    @Test
    void ignoresEverythingWhenTheTriggerIsSwitchedOff() {
        CmsPurgeWebhookResult result = useCases(trigger(false, Duration.ZERO), purgeScope(true))
                .apply(delivery("page_blp", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).contains("cms-purge-webhook.enabled");
        verifyNoInteractions(cachePurgeService);
    }

    /**
     * The check that keeps a developer's laptop off the live edge. {@code cache-purge.enabled} is
     * applied by the ops <em>controller</em>, not by the purge use case, so this second caller of the
     * port does not inherit it and has to honour it itself.
     */
    @Test
    void ignoresEverythingWhenCachePurgeIsSwitchedOffServiceWide() {
        CmsPurgeWebhookResult result = useCases(trigger(true, Duration.ZERO), purgeScope(false))
                .apply(delivery("page_blp", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).contains("cache-purge.enabled");
        verifyNoInteractions(cachePurgeService);
    }

    /** A rejected purge is reported, never rethrown: a 5xx here is a Contentstack retry loop. */
    @Test
    void reportsACdnRejectionWithoutThrowing() {
        doThrow(CachePurgeFailedException.upstream("The CDN rejected the purge credential.",
                "Akamai answered 403.", 403, "support-9", null))
                .when(cachePurgeService).purgeAll(any(), any());

        CmsPurgeWebhookResult result = enabledUseCases().apply(delivery("page_blp", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).isEqualTo("The CDN rejected the purge credential.");
        assertThat(result.action()).isEqualTo(PurgeAction.INVALIDATE);
        assertThat(result.objectCount()).isZero();
    }

    /** Nothing logs this one downstream — the port throws before any Akamai call is attempted. */
    @Test
    void reportsAnUnconfiguredCredentialWithoutThrowing() {
        doThrow(new CachePurgeNotConfiguredException()).when(cachePurgeService).purgeAll(any(), any());

        CmsPurgeWebhookResult result = enabledUseCases().apply(delivery("page_clp", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).isNotBlank();
    }

    /** An untyped failure is a defect here rather than an upstream outage; it still may not escape. */
    @Test
    void reportsAnUnexpectedFailureWithoutThrowing() {
        doThrow(new IllegalStateException("boom")).when(cachePurgeService).purgeAll(any(), any());

        CmsPurgeWebhookResult result = enabledUseCases().apply(delivery("page_blp", "publish"));

        assertThat(result.purged()).isFalse();
        assertThat(result.reason()).isEqualTo("boom");
    }

    /**
     * Publishing the Fashion Fest template delivers one webhook per block. With a cooldown configured
     * only the first submits; the rest are covered by it.
     */
    @Test
    void collapsesABurstOfDeliveriesIntoOnePurge() {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());
        CmsPurgeWebhookUseCases useCases = useCases(trigger(true, Duration.ofSeconds(10)), purgeScope(true));

        CmsPurgeWebhookResult first = useCases.apply(delivery("page_clp", "publish"));
        CmsPurgeWebhookResult second = useCases.apply(delivery("page_clp", "publish"));
        CmsPurgeWebhookResult third = useCases.apply(delivery("page_blp", "publish"));

        assertThat(first.purged()).isTrue();
        assertThat(second.purged()).isFalse();
        assertThat(second.reason()).contains("less than 10s ago");
        assertThat(third.purged()).isFalse();
        verify(cachePurgeService, times(1)).purgeAll(any(), any());
    }

    @Test
    void purgesAgainOnceTheCooldownHasElapsed() {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());
        CmsPurgeWebhookProperties trigger = trigger(true, Duration.ofSeconds(10));
        MutableClock clock = new MutableClock(NOW);
        CmsPurgeWebhookUseCases useCases =
                new CmsPurgeWebhookUseCases(cachePurgeService, trigger, purgeScope(true), clock);

        assertThat(useCases.apply(delivery("page_blp", "publish")).purged()).isTrue();
        clock.advance(Duration.ofSeconds(11));
        assertThat(useCases.apply(delivery("page_blp", "publish")).purged()).isTrue();

        verify(cachePurgeService, times(2)).purgeAll(any(), any());
    }

    /** A zero cooldown switches the collapsing off entirely; every delivery purges. */
    @Test
    void purgesEveryDeliveryWhenTheCooldownIsZero() {
        doReturn(report(3)).when(cachePurgeService).purgeAll(any(), any());
        CmsPurgeWebhookUseCases useCases = enabledUseCases();

        assertThat(useCases.apply(delivery("page_blp", "publish")).purged()).isTrue();
        assertThat(useCases.apply(delivery("page_blp", "publish")).purged()).isTrue();

        verify(cachePurgeService, times(2)).purgeAll(any(), any());
    }

    /**
     * A failed purge still consumes the cooldown slot: a CDN that is rate limiting or refusing the
     * credential must not then be hit once per entry of a bulk publish.
     */
    @Test
    void aFailedPurgeStillConsumesTheCooldown() {
        doThrow(new CachePurgeNotConfiguredException()).when(cachePurgeService).purgeAll(any(), any());
        CmsPurgeWebhookUseCases useCases = useCases(trigger(true, Duration.ofSeconds(10)), purgeScope(true));

        useCases.apply(delivery("page_blp", "publish"));
        CmsPurgeWebhookResult second = useCases.apply(delivery("page_blp", "publish"));

        assertThat(second.reason()).contains("less than 10s ago");
        verify(cachePurgeService, times(1)).purgeAll(any(), any());
    }

    /** Configuration with no content types switches the trigger off in practice, rather than opening it up. */
    @Test
    void purgesNothingWhenNoContentTypeIsConfigured() {
        CmsPurgeWebhookProperties none = new CmsPurgeWebhookProperties(List.of(),
                PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION, Duration.ZERO, true);

        assertThat(useCases(none, purgeScope(true)).apply(delivery("page_blp", "publish")).purged()).isFalse();
        verify(cachePurgeService, never()).purgeAll(any(), any());
    }

    /** The action and network come from this trigger's own keys, not from the operator defaults. */
    @Test
    void usesTheTriggersOwnActionAndNetwork() {
        doReturn(report(1)).when(cachePurgeService).purgeAll(any(), any());
        CmsPurgeWebhookProperties staging = new CmsPurgeWebhookProperties(List.of("page_blp"),
                PurgeAction.DELETE, PurgeNetwork.STAGING, Duration.ZERO, true);

        useCases(staging, purgeScope(true)).apply(delivery("page_blp", "publish"));

        verify(cachePurgeService).purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING);
    }

    /** A clock that can be moved forward, so the cooldown is testable without sleeping. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
