package com.liverpool.ms_clp_blp.landing.brandflags;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.adapters.featureflag.FeatureFlagAdapter;
import com.liverpool.ms_clp_blp.landing.brandflags.adapters.cache.BrandFlagsCache;
import com.liverpool.ms_clp_blp.landing.brandflags.adapters.defaults.JsonOverrideBrandFlagsDefaults;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsContentPort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Walks the whole per-brand flag flow end to end, with the real cache, the real precedence chain and
 * the real use case — only the two outbound ports are doubles.
 *
 * <p>Every other test in this package pins one link. This one exists because the links can each be
 * correct while the chain is not: a publish that reaches Mongo but never evicts the cache, a cache
 * that expires but re-reads the wrong key, a fallback that fires when the store merely had nothing to
 * say. Those are wiring bugs, and only a test that spans the wiring can see them.</p>
 */
class BrandFlagsFlowTest {

    private static final String BRAND = "GAP";
    private static final Duration TTL = Duration.ofMillis(60);

    private final RecordingRepository repository = new RecordingRepository();
    private final StubContent content = new StubContent();

    /**
     * Steps 1 to 3: Contentstack calls the webhook, the receiving instance reads that brand's entry
     * from the content-service, and stores it as one document keyed by brand.
     */
    @Test
    void aPublishReadsThatBrandsEntryAndStoresIt() {
        content.publish(BRAND, Map.of(LandingFeatureFlags.JEWEL, false));

        BrandFlagsSyncResult result = useCases().apply("gap", publish());

        assertThat(result.applied()).isTrue();
        assertThat(result.action()).isEqualTo(BrandFlagsSyncResult.ACTION_UPSERT);
        assertThat(content.askedFor).containsExactly(BRAND);
        assertThat(repository.stored).containsKey(BRAND);
        assertThat(repository.stored.get(BRAND)).containsEntry(LandingFeatureFlags.JEWEL, false);
    }

    /** Step 3, the guard: nothing usable from the CMS must leave the stored configuration alone. */
    @Test
    void aPublishThatResolvesNoContentWritesNothing() {
        repository.stored.put(BRAND, Map.of(LandingFeatureFlags.JEWEL, false));
        content.resolvesNothing();

        BrandFlagsSyncResult result = useCases().apply(BRAND, publish());

        assertThat(result.applied()).isFalse();
        assertThat(repository.stored.get(BRAND)).containsEntry(LandingFeatureFlags.JEWEL, false);
    }

    /**
     * Step 4: the first evaluation reads the store, the next ones are served from instance memory,
     * and once the TTL passes the store is read again.
     */
    @Test
    void theStoreIsReadOncePerTtlAndServedFromMemoryInBetween() throws InterruptedException {
        repository.stored.put(BRAND, Map.of(LandingFeatureFlags.JEWEL, false));
        BrandFlagsCache cache = cache();
        FeatureFlagPort flags = flags(cache, "");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(repository.reads.get()).isEqualTo(1);

        Thread.sleep(TTL.toMillis() * 2);
        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(repository.reads.get()).isEqualTo(2);
    }

    /** Step 4, the other half: a publish makes the change visible on this instance immediately. */
    @Test
    void aPublishMakesTheNewValueVisibleWithoutWaitingForTheTtl() {
        repository.stored.put(BRAND, Map.of(LandingFeatureFlags.JEWEL, true));
        BrandFlagsCache cache = cache();
        FeatureFlagPort flags = flags(cache, "");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();

        content.publish(BRAND, Map.of(LandingFeatureFlags.JEWEL, false));
        useCases(cache).apply(BRAND, publish());

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
    }

    /**
     * Step 5: with the store unreachable, the flag resolves from {@code FF_OVERRIDE_JSON} for the
     * brand that names it, and from {@code landing.feature-flags} for everything else. Reading
     * "unreachable" as "disabled" would blank the page for every brand at once.
     */
    @Test
    void anUnreachableStoreFallsBackToTheOverrideAndThenToTheServiceWideValues() {
        repository.unavailable();
        FeatureFlagPort flags = flags(cache(), """
                {"jewel_GAP": false}""");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(flags.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isTrue();
        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, "LP")).isTrue();
    }

    /** Step 5, without any override configured: everything comes from the YAML, as it always did. */
    @Test
    void anUnreachableStoreWithNoOverrideBehavesExactlyAsBeforeThisFeature() {
        repository.unavailable();
        FeatureFlagPort flags = flags(cache(), "");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(flags.isEnabled(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, BRAND)).isFalse();
    }

    /**
     * The failure mode the others miss: Mongo answers correctly, then dies. Once the cached entry
     * expires the refresh fails, and the brand must land on its configured values rather than on
     * whatever it happened to be serving — bounded by the TTL, and predictable.
     */
    @Test
    void aStoreThatDiesAfterAGoodReadDegradesToTheConfiguredValuesWithinOneTtl()
            throws InterruptedException {
        repository.stored.put(BRAND, Map.of(LandingFeatureFlags.JEWEL, false));
        FeatureFlagPort flags = flags(cache(), """
                {"jewel_GAP": true, "vertex_GAP": false}""");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();

        repository.unavailable();

        // Still the stored value: the entry has not expired yet, so nothing has been re-read.
        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();

        Thread.sleep(TTL.toMillis() * 2);

        // Expired, refresh failed: the override answers, and the flag the override does not name
        // falls all the way through to landing.feature-flags.
        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(flags.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isFalse();
        assertThat(flags.isEnabled(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, BRAND)).isFalse();
    }

    /**
     * A dead store must not be hammered. The failed read is cached exactly like a successful one, so
     * an outage costs one round-trip per brand per TTL instead of one per request — which is the
     * difference between a slow page and a page that times out.
     */
    @Test
    void aDeadStoreIsNotRetriedOnEveryRequest() {
        repository.unavailable();
        FeatureFlagPort flags = flags(cache(), "");

        for (int i = 0; i < 20; i++) {
            flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND);
        }

        assertThat(repository.reads.get()).isEqualTo(1);
    }

    /**
     * The precedence that decides everything: a stored value is more current than a packaged
     * override, so it wins even when both name the same flag.
     */
    @Test
    void aStoredValueBeatsTheOverride() {
        repository.stored.put(BRAND, Map.of(LandingFeatureFlags.JEWEL, true));
        FeatureFlagPort flags = flags(cache(), """
                {"jewel_GAP": false}""");

        assertThat(flags.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
    }

    // ── wiring ───────────────────────────────────────────────────────────────────────────────────

    private BrandFlagsCache cache() {
        return new BrandFlagsCache(repository, properties(""));
    }

    private BrandFlagsUseCases useCases() {
        return useCases(cache());
    }

    private BrandFlagsUseCases useCases(BrandFlagsCache cache) {
        return new BrandFlagsUseCases(content, repository, cache, properties(""), landingProperties());
    }

    private FeatureFlagPort flags(BrandFlagsCache cache, String overrideJson) {
        return new FeatureFlagAdapter(landingProperties(), cache,
                new JsonOverrideBrandFlagsDefaults(properties(overrideJson), new ObjectMapper()));
    }

    // The service-wide values these tests fall back to: jewel on, vertex on, greeting off.
    private static LandingProperties landingProperties() {
        return new LandingProperties(Map.of(
                LandingFeatureFlags.JEWEL, true,
                LandingFeatureFlags.VERTEX, true,
                LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, false), Map.of(), null);
    }

    private static BrandFlagsProperties properties(String overrideJson) {
        return new BrandFlagsProperties(
                true, "ms_clp_blp_flags", "es-mx", "ms_clp_blp_configuration", TTL, List.of(), overrideJson);
    }

    private static Map<String, Object> publish() {
        return Map.of("event", "publish",
                "data", Map.of("content_type", Map.of("uid", "ms_clp_blp_flags")));
    }

    /** Stands in for MongoDB, and can be told to behave as if it were unreachable. */
    private static final class RecordingRepository implements BrandFlagsRepositoryPort {

        private final Map<String, Map<String, Boolean>> stored = new HashMap<>();
        private final AtomicInteger reads = new AtomicInteger();
        private boolean reachable = true;

        private void unavailable() {
            reachable = false;
        }

        @Override
        public Optional<BrandFlags> findByBrand(String brand) {
            reads.incrementAndGet();
            // An unreachable store answers exactly like a brand with no document, by design: the
            // caller would do the same thing either way, so it is never given the chance to differ.
            if (!reachable) {
                return Optional.empty();
            }
            return Optional.ofNullable(stored.get(brand))
                    .map(flags -> new BrandFlags(brand, flags, Instant.EPOCH, "blt1"));
        }

        @Override
        public boolean save(BrandFlags brandFlags) {
            if (!reachable) {
                return false;
            }
            stored.put(brandFlags.brand(), new LinkedHashMap<>(brandFlags.flags()));
            return true;
        }

        @Override
        public boolean saveDefaults(BrandFlags brandFlags) {
            return save(brandFlags);
        }

        @Override
        public boolean delete(String brand) {
            if (!reachable) {
                return false;
            }
            stored.remove(brand);
            return true;
        }
    }

    /** Stands in for the content-service, recording which brand was asked for. */
    private static final class StubContent implements BrandFlagsContentPort {

        private final java.util.List<String> askedFor = new java.util.ArrayList<>();
        private BrandFlags authored;

        private void publish(String brand, Map<String, Boolean> flags) {
            this.authored = new BrandFlags(brand, flags, Instant.EPOCH, "blt1");
        }

        private void resolvesNothing() {
            this.authored = null;
        }

        @Override
        public Optional<BrandFlags> fetchForBrand(String brand) {
            askedFor.add(brand);
            return Optional.ofNullable(authored);
        }
    }
}
