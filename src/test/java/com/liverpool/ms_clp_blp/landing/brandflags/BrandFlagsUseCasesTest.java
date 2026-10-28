package com.liverpool.ms_clp_blp.landing.brandflags;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsCachePort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsContentPort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The webhook is the only writer, so these cases are the whole of how configuration enters the system
 * — including the ones where it deliberately does not.
 */
class BrandFlagsUseCasesTest {

    private static final String CONTENT_TYPE = "ms_clp_blp_flags";

    private final BrandFlagsContentPort contentPort = mock(BrandFlagsContentPort.class);
    private final BrandFlagsRepositoryPort repository = mock(BrandFlagsRepositoryPort.class);
    private final BrandFlagsCachePort cache = mock(BrandFlagsCachePort.class);

    @Test
    void storesTheEntryOnAPublishAndEvictsTheLocalCopy() {
        BrandFlags authored = new BrandFlags(
                "GAP", Map.of(LandingFeatureFlags.JEWEL, false), Instant.EPOCH, "blt1");
        doReturn(Optional.of(authored)).when(contentPort).fetchForBrand("GAP");
        doReturn(true).when(repository).save(any());

        BrandFlagsSyncResult result = useCases(true).apply("gap", publish(CONTENT_TYPE));

        assertThat(result.applied()).isTrue();
        assertThat(result.action()).isEqualTo(BrandFlagsSyncResult.ACTION_UPSERT);
        assertThat(result.brand()).isEqualTo("GAP");
        verify(repository).save(authored);
        verify(cache).evict("GAP");
    }

    /** An unpublish returns the brand to the service defaults rather than freezing the last values. */
    @Test
    void removesTheConfigurationOnUnpublish() {
        doReturn(true).when(repository).delete("GAP");

        BrandFlagsSyncResult result = useCases(true).apply("GAP", event("unpublish", CONTENT_TYPE));

        assertThat(result.applied()).isTrue();
        assertThat(result.action()).isEqualTo(BrandFlagsSyncResult.ACTION_DELETE);
        verify(cache).evict("GAP");
        verifyNoInteractions(contentPort);
    }

    @Test
    void removesTheConfigurationOnDelete() {
        doReturn(true).when(repository).delete("GAP");

        assertThat(useCases(true).apply("GAP", event("entry.delete", CONTENT_TYPE)).action())
                .isEqualTo(BrandFlagsSyncResult.ACTION_DELETE);
    }

    /**
     * A stack may be configured to notify us about everything. Those deliveries must be acknowledged,
     * not failed — Contentstack retries every non-2xx, so a rejection becomes a retry loop.
     */
    @Test
    void ignoresAnUnrelatedContentType() {
        BrandFlagsSyncResult result = useCases(true).apply("GAP", publish("page"));

        assertThat(result.applied()).isFalse();
        assertThat(result.action()).isEqualTo(BrandFlagsSyncResult.ACTION_IGNORED);
        verifyNoInteractions(contentPort, repository, cache);
    }

    /** Contentstack has delivered the content type in three different positions over time. */
    @Test
    void findsTheContentTypeWhereverItAppears() {
        doReturn(Optional.of(new BrandFlags("GAP", Map.of(LandingFeatureFlags.JEWEL, true),
                Instant.EPOCH, "blt1"))).when(contentPort).fetchForBrand(anyString());
        doReturn(true).when(repository).save(any());

        assertThat(useCases(true).apply("GAP",
                Map.of("event", "publish", "content_type_uid", CONTENT_TYPE)).applied()).isTrue();
        assertThat(useCases(true).apply("GAP",
                Map.of("event", "publish", "data", Map.of("content_type_uid", CONTENT_TYPE)))
                .applied()).isTrue();
        assertThat(useCases(true).apply("GAP", publish(CONTENT_TYPE)).applied()).isTrue();
    }

    /**
     * The case that protects a brand from a mis-authored publish: nothing usable came back, so what is
     * already stored stays. Writing an empty document here would silently reset the brand.
     */
    @Test
    void leavesTheStoredConfigurationAloneWhenNothingUsableWasRead() {
        doReturn(Optional.empty()).when(contentPort).fetchForBrand("GAP");

        BrandFlagsSyncResult result = useCases(true).apply("GAP", publish(CONTENT_TYPE));

        assertThat(result.applied()).isFalse();
        verify(repository, never()).save(any());
        verify(cache, never()).evict(anyString());
    }

    @Test
    void reportsAFailedWriteWithoutEvicting() {
        doReturn(Optional.of(new BrandFlags("GAP", Map.of(LandingFeatureFlags.JEWEL, true),
                Instant.EPOCH, "blt1"))).when(contentPort).fetchForBrand("GAP");
        doReturn(false).when(repository).save(any());

        assertThat(useCases(true).apply("GAP", publish(CONTENT_TYPE)).applied()).isFalse();
        verify(cache, never()).evict(anyString());
    }

    @Test
    void reportsAFailedRemovalWithoutEvicting() {
        doReturn(false).when(repository).delete("GAP");

        assertThat(useCases(true).apply("GAP", event("unpublish", CONTENT_TYPE)).applied()).isFalse();
        verify(cache, never()).evict(anyString());
    }

    /** No brand means no document id; there is nothing this delivery could be about. */
    @Test
    void ignoresADeliveryWithNoBrand() {
        assertThat(useCases(true).apply(null, publish(CONTENT_TYPE)).applied()).isFalse();
        assertThat(useCases(true).apply("  ", publish(CONTENT_TYPE)).applied()).isFalse();
        verifyNoInteractions(contentPort, repository, cache);
    }

    /** With the feature off the webhook must be inert, not merely ineffective. */
    @Test
    void doesNothingWhenTheFeatureIsSwitchedOff() {
        assertThat(useCases(false).apply("GAP", publish(CONTENT_TYPE)).applied()).isFalse();
        verifyNoInteractions(contentPort, repository, cache);
    }

    /** A delivery with no recognisable content type is not ours either. */
    @Test
    void ignoresAPayloadItCannotClassify() {
        assertThat(useCases(true).apply("GAP", Map.of()).applied()).isFalse();
        verifyNoInteractions(contentPort, repository, cache);
    }

    /** A brand with no document gets one, carrying the current landing.feature-flags snapshot. */
    @Test
    void seedsABrandThatHasNoDocumentYet() {
        doReturn(Optional.empty()).when(repository).findByBrand("GAP");
        doReturn(true).when(repository).saveDefaults(any());

        useCases(true, List.of("gap")).seedDefaults();

        org.mockito.ArgumentCaptor<BrandFlags> written =
                org.mockito.ArgumentCaptor.forClass(BrandFlags.class);
        verify(repository).saveDefaults(written.capture());
        assertThat(written.getValue().brand()).isEqualTo("GAP");
        assertThat(written.getValue().flags()).isEqualTo(Map.of(LandingFeatureFlags.JEWEL, true));
        assertThat(written.getValue().entryUid()).isNull();
    }

    /** A brand that already has a document — CMS-authored or previously seeded — is left alone. */
    @Test
    void skipsABrandThatAlreadyHasADocument() {
        doReturn(Optional.of(new BrandFlags("GAP", Map.of(), Instant.EPOCH, "blt1")))
                .when(repository).findByBrand("GAP");

        useCases(true, List.of("gap")).seedDefaults();

        verify(repository, never()).saveDefaults(any());
    }

    /** Every configured brand is checked, not just the first. */
    @Test
    void seedsEveryConfiguredBrandIndependently() {
        doReturn(Optional.of(new BrandFlags("LP", Map.of(), Instant.EPOCH, "blt1")))
                .when(repository).findByBrand("LP");
        doReturn(Optional.empty()).when(repository).findByBrand("GAP");
        doReturn(true).when(repository).saveDefaults(any());

        useCases(true, List.of("lp", "gap")).seedDefaults();

        verify(repository, times(1)).saveDefaults(any());
    }

    /** A failed write is logged, not thrown, so one bad brand does not stop the rest of the boot. */
    @Test
    void doesNotThrowWhenTheDefaultWriteFails() {
        doReturn(Optional.empty()).when(repository).findByBrand("GAP");
        doReturn(false).when(repository).saveDefaults(any());

        useCases(true, List.of("gap")).seedDefaults();

        verify(repository).saveDefaults(any());
    }

    /** No brands configured means nothing is checked. */
    @Test
    void touchesNothingWhenNoBrandsAreConfigured() {
        useCases(true, List.of()).seedDefaults();

        verifyNoInteractions(repository);
    }

    private BrandFlagsUseCases useCases(boolean enabled) {
        return useCases(enabled, List.of());
    }

    private BrandFlagsUseCases useCases(boolean enabled, List<String> seedBrands) {
        return new BrandFlagsUseCases(
                contentPort, repository, cache, properties(enabled, seedBrands), landingProperties());
    }

    private static Map<String, Object> publish(String contentType) {
        return event("publish", contentType);
    }

    private static Map<String, Object> event(String event, String contentType) {
        return Map.of("event", event,
                "data", Map.of(
                        "content_type", Map.of("uid", contentType),
                        "entry", Map.of("uid", "blt1", "identifier", "ms_clp_blp_flags_GAP")));
    }

    private static BrandFlagsProperties properties(boolean enabled) {
        return properties(enabled, List.of());
    }

    private static BrandFlagsProperties properties(boolean enabled, List<String> seedBrands) {
        return new BrandFlagsProperties(enabled, CONTENT_TYPE, "es-mx", "ms_clp_blp_configuration",
                Duration.ofMinutes(3), seedBrands, "");
    }

    private static LandingProperties landingProperties() {
        return new LandingProperties(Map.of(LandingFeatureFlags.JEWEL, true), Map.of(), null);
    }
}
