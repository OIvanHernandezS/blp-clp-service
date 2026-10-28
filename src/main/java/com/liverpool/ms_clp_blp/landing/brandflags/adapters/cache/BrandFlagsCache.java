package com.liverpool.ms_clp_blp.landing.brandflags.adapters.cache;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsCachePort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsLookupPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Per-instance, per-brand copy of the stored feature flags, refreshed on expiry.
 *
 * <p><strong>Refreshed on the request path, never on a schedule.</strong> Cloud Run throttles CPU
 * between requests, so a {@code @Scheduled} refresh would run unpredictably or not at all; and an
 * instance that has just started needs the value for its very first request regardless. Reading here
 * costs one store round-trip per brand per TTL.</p>
 *
 * <p><strong>Propagation is bounded by the TTL, not instant.</strong> The webhook evicts only the
 * instance that received it — Cloud Run gives no way to address the others. Every other instance
 * converges within {@link BrandFlagsProperties#cacheTtl()}. That is the deliberate trade: no message
 * broker, no always-allocated CPU, no per-instance subscriptions.</p>
 *
 * <p>Concurrency: entries are immutable and published through a {@link ConcurrentHashMap}, so readers
 * never lock. A refresh takes a per-brand {@link ReentrantLock} with {@code tryLock} so a burst of
 * concurrent requests for one brand produces one store call rather than one per request; the losers
 * serve the expired entry instead of queuing. Only a cold start, where there is nothing to serve,
 * waits. {@code ReentrantLock} does not pin a virtual thread's carrier on Java 21.</p>
 */
@Component
public class BrandFlagsCache implements BrandFlagsLookupPort, BrandFlagsCachePort {

    private static final Logger log = LoggerFactory.getLogger(BrandFlagsCache.class);

    /** Log field: whether the refreshed entry carried stored overrides or was a negative result. */
    private static final String STORED = "stored";

    /** Log field: how many overrides the brand declares. */
    private static final String FLAG_COUNT = "flagCount";

    /** Log field: how many numeric settings the brand declares. */
    private static final String SETTING_COUNT = "settingCount";

    private final Map<String, Entry> byBrand = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final BrandFlagsRepositoryPort repository;
    private final BrandFlagsProperties properties;

    /**
     * @param repository the durable store; returns empty rather than throwing when unreachable
     * @param properties supplies the TTL and the kill switch
     */
    public BrandFlagsCache(BrandFlagsRepositoryPort repository, BrandFlagsProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<Boolean> lookup(String flagId, String brand) {
        if (!properties.enabled() || brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entryFor(normalize(brand)).flags().get(flagId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Served from the same entry, and so the same TTL and the same single store read, as the flags.</p>
     */
    @Override
    public Optional<Integer> lookupSetting(String settingId, String brand) {
        if (!properties.enabled() || brand == null || brand.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entryFor(normalize(brand)).settings().get(settingId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Called by the webhook on the instance that received it; the others expire on their own.</p>
     */
    @Override
    public void evict(String brand) {
        if (brand != null && !brand.isBlank()) {
            byBrand.remove(normalize(brand));
        }
    }

    private Entry entryFor(String brand) {
        Entry current = byBrand.get(brand);
        if (current != null && !current.isExpired()) {
            return current;
        }
        ReentrantLock lock = locks.computeIfAbsent(brand, key -> new ReentrantLock());
        if (!lock.tryLock()) {
            // Another thread is already refreshing this brand. Serving the expired copy is both
            // faster and no less correct than waiting for it — the value is at most one TTL old
            // either way. With nothing to serve, fall through to an empty entry rather than block.
            return current == null ? Entry.empty(properties.cacheTtl().toNanos()) : current;
        }
        try {
            // Re-check: a thread that was waiting on the lock must not repeat the store call.
            Entry refreshed = byBrand.get(brand);
            if (refreshed != null && !refreshed.isExpired()) {
                return refreshed;
            }
            return load(brand);
        } finally {
            lock.unlock();
        }
    }

    private Entry load(String brand) {
        Optional<BrandFlags> stored = read(brand);
        // A miss is cached exactly like a hit, and that is the point: a brand with no document, and a
        // store that is down, both stop being asked for a full TTL instead of paying the round-trip on
        // every single request.
        Entry entry = new Entry(
                stored.map(BrandFlags::flags).orElseGet(Map::of),
                stored.map(BrandFlags::settings).orElseGet(Map::of),
                System.nanoTime() + properties.cacheTtl().toNanos());
        byBrand.put(brand, entry);
        LogEvents.at(log, LogEvents.BRAND_FLAGS_CACHE_REFRESHED)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(STORED, stored.isPresent())
                .addKeyValue(FLAG_COUNT, entry.flags().size())
                .addKeyValue(SETTING_COUNT, entry.settings().size())
                .log("Per-brand feature flags refreshed from the store");
        return entry;
    }

    /**
     * Belt to the repository's braces. {@link BrandFlagsRepositoryPort} documents that implementations
     * translate failure into an empty result, and the Mongo adapter does — but that is a contract in a
     * comment, and this method is the last thing between a repository and {@code GET /blp|clp/**}. A future
     * adapter that forgets it would turn a flag store outage into a 500 on a landing page, which is the
     * exact failure this whole design exists to prevent. Logged, never silent.
     */
    private Optional<BrandFlags> read(String brand) {
        try {
            return repository.findByBrand(brand);
        } catch (RuntimeException e) {
            LogEvents.at(log, LogEvents.BRAND_FLAGS_STORE_UNAVAILABLE)
                    .addKeyValue(LogFields.BRAND, brand)
                    .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                    .setCause(e)
                    .log("Per-brand flag store threw instead of returning an empty result, which its "
                            + "port forbids; flags fall back to their configured values");
            return Optional.empty();
        }
    }

    private static String normalize(String brand) {
        return brand.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * One brand's overrides plus the instant they stop being trusted.
     *
     * @param flags         flag id to value; empty means "this brand overrides nothing"
     * @param settings      numeric setting id to value; empty means "this brand overrides nothing"
     * @param expiresAtNanos {@link System#nanoTime()} reading after which the entry must be reloaded
     */
    private record Entry(Map<String, Boolean> flags, Map<String, Integer> settings, long expiresAtNanos) {

        static Entry empty(long ttlNanos) {
            return new Entry(Map.of(), Map.of(), System.nanoTime() + ttlNanos);
        }

        boolean isExpired() {
            // Subtraction rather than comparison: nanoTime may be negative and wraps, so `now > expiry`
            // is wrong at the boundary while the difference's sign is always right.
            return System.nanoTime() - expiresAtNanos >= 0;
        }
    }
}
