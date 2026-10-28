package com.liverpool.ms_clp_blp.landing.brandflags.adapters.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cache is the only part of this feature on the {@code GET /blp|clp/**} path, so its job is to keep the
 * store off that path almost always, and to be wrong in a bounded way when it is stale.
 */
class BrandFlagsCacheTest {

    private static final String JEWEL = "jewel";
    private static final String VERTEX = "vertex";
    private static final String MAX_IMAGES = "products-list-max-images";

    /** A brand's flags are read once and served from memory afterwards. */
    @Test
    void readsTheStoreOncePerBrandAndThenServesFromMemory() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, "LP")).contains(false);
        assertThat(cache.lookup(JEWEL, "LP")).contains(false);
        assertThat(cache.lookup(JEWEL, "LP")).contains(false);

        assertThat(repository.calls("LP")).isEqualTo(1);
    }

    /**
     * A brand with no document must be cached exactly like one with a document. Otherwise every
     * request for an unconfigured brand — the common case during rollout — pays a store round-trip.
     */
    @Test
    void cachesTheAbsenceOfADocumentToo() {
        CountingRepository repository = new CountingRepository(Map.of());
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, "GAP")).isEmpty();
        assertThat(cache.lookup(JEWEL, "GAP")).isEmpty();

        assertThat(repository.calls("GAP")).isEqualTo(1);
    }

    /** An expired entry is reloaded; the TTL is what bounds how stale an instance can be. */
    @Test
    void reloadsAfterTheTtlExpires() throws InterruptedException {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMillis(20)));

        assertThat(cache.lookup(JEWEL, "LP")).contains(false);
        Thread.sleep(40);
        assertThat(cache.lookup(JEWEL, "LP")).contains(false);

        assertThat(repository.calls("LP")).isEqualTo(2);
    }

    /** One brand's entry must never answer for another; they are separate documents. */
    @Test
    void keepsBrandsIsolated() {
        CountingRepository repository = new CountingRepository(Map.of(
                "LP", Map.of(JEWEL, true),
                "GAP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, "LP")).contains(true);
        assertThat(cache.lookup(JEWEL, "GAP")).contains(false);
    }

    /** The brand arrives in a header, so its case is whatever the caller sent. */
    @Test
    void matchesTheBrandRegardlessOfCaseOrPadding() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, "lp")).contains(false);
        assertThat(cache.lookup(JEWEL, " Lp ")).contains(false);
        assertThat(repository.calls("LP")).isEqualTo(1);
    }

    /** Eviction is what makes the change visible immediately on the instance that got the webhook. */
    @Test
    void evictionForcesTheNextLookupToReadTheStore() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        cache.lookup(JEWEL, "LP");
        cache.evict("lp");
        cache.lookup(JEWEL, "LP");

        assertThat(repository.calls("LP")).isEqualTo(2);
    }

    /** A flag the brand does not declare must read as absent so the caller falls back to defaults. */
    @Test
    void reportsAnUndeclaredFlagAsAbsent() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(VERTEX, "LP")).isEmpty();
    }

    /** The kill switch must cost nothing: no store call at all, not merely an ignored result. */
    @Test
    void neverTouchesTheStoreWhenSwitchedOff() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(false, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, "LP")).isEmpty();
        assertThat(repository.calls("LP")).isZero();
    }

    /** No session resolved means no brand; there is nothing to look up and nothing to cache. */
    @Test
    void skipsLookupWhenTheBrandIsMissing() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookup(JEWEL, null)).isEmpty();
        assertThat(cache.lookup(JEWEL, "  ")).isEmpty();
        assertThat(repository.totalCalls()).isZero();
    }

    /** Evicting a brand that was never cached, or no brand at all, must not blow up. */
    @Test
    void tolerateEvictingNothing() {
        BrandFlagsCache cache = new BrandFlagsCache(
                new CountingRepository(Map.of()), properties(true, Duration.ofMinutes(3)));

        cache.evict(null);
        cache.evict("  ");
        cache.evict("NEVER_SEEN");
    }

    /**
     * A burst of concurrent requests for one cold brand must not become a burst of store calls. This
     * is the stampede the per-brand lock exists to stop; with the store slow or down it is the
     * difference between one timeout and one per in-flight request.
     */
    @Test
    void collapsesAConcurrentBurstIntoASingleStoreRead() throws InterruptedException {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        repository.delayMillis = 30;
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        int threads = 16;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = Thread.ofVirtual().start(() -> cache.lookup(JEWEL, "LP"));
        }
        for (Thread worker : workers) {
            worker.join();
        }

        assertThat(repository.calls("LP")).isEqualTo(1);
    }

    /** Settings ride in the same entry as the flags: one store read answers both. */
    @Test
    void servesSettingsFromTheSameEntryAsTheFlags() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of(JEWEL, false)));
        repository.settings = Map.of(MAX_IMAGES, 3);
        BrandFlagsCache cache = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));

        assertThat(cache.lookupSetting(MAX_IMAGES, "lp")).contains(3);
        assertThat(cache.lookup(JEWEL, "LP")).contains(false);
        assertThat(cache.lookupSetting("other", "LP")).isEmpty();

        assertThat(repository.calls("LP")).isEqualTo(1);
    }

    @Test
    void answersNoSettingWhenDisabledOrBrandless() {
        CountingRepository repository = new CountingRepository(Map.of("LP", Map.of()));
        repository.settings = Map.of(MAX_IMAGES, 3);

        assertThat(new BrandFlagsCache(repository, properties(false, Duration.ofMinutes(3)))
                .lookupSetting(MAX_IMAGES, "LP")).isEmpty();
        BrandFlagsCache enabled = new BrandFlagsCache(repository, properties(true, Duration.ofMinutes(3)));
        assertThat(enabled.lookupSetting(MAX_IMAGES, null)).isEmpty();
        assertThat(enabled.lookupSetting(MAX_IMAGES, " ")).isEmpty();

        assertThat(repository.totalCalls()).isZero();
    }

    private static BrandFlagsProperties properties(boolean enabled, Duration ttl) {
        return new BrandFlagsProperties(
                enabled, "ms_clp_blp_flags", "es-mx", "ms_clp_blp_configuration", ttl, List.of(), "");
    }

    /** Records how often each brand was read, so "did this hit the store?" is directly assertable. */
    private static final class CountingRepository implements BrandFlagsRepositoryPort {

        private final Map<String, Map<String, Boolean>> stored;
        private final Map<String, AtomicInteger> calls = new java.util.concurrent.ConcurrentHashMap<>();
        private long delayMillis;
        private Map<String, Integer> settings = Map.of();

        private CountingRepository(Map<String, Map<String, Boolean>> stored) {
            this.stored = stored;
        }

        @Override
        public Optional<BrandFlags> findByBrand(String brand) {
            calls.computeIfAbsent(brand, key -> new AtomicInteger()).incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Optional.ofNullable(stored.get(brand))
                    .map(flags -> new BrandFlags(brand, flags, Instant.EPOCH, "blt1", settings));
        }

        @Override
        public boolean save(BrandFlags brandFlags) {
            return true;
        }

        @Override
        public boolean saveDefaults(BrandFlags brandFlags) {
            return true;
        }

        @Override
        public boolean delete(String brand) {
            return true;
        }

        private int calls(String brand) {
            return calls.getOrDefault(brand, new AtomicInteger()).get();
        }

        private int totalCalls() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }
    }
}
