package com.liverpool.ms_clp_blp.landing.brandflags;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlags;
import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsCachePort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsContentPort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsRepositoryPort;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsSyncService;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Applies a Contentstack webhook delivery to the per-brand flag store.
 *
 * <p>Mirrors the semantics platform-commons already established for these webhooks: an event naming
 * {@code delete} or {@code unpublish} removes the configuration, anything else re-reads and stores it.
 * The content type is resolved from three possible positions in the payload for the same reason —
 * Contentstack has delivered it in all of them.</p>
 *
 * <p>Every path is idempotent, because Contentstack retries. A repeated publish rewrites the same
 * document; a repeated delete removes what is already gone.</p>
 */
@Service
public class BrandFlagsUseCases implements BrandFlagsSyncService {

    private static final Logger log = LoggerFactory.getLogger(BrandFlagsUseCases.class);

    /** Log field: the Contentstack event that triggered the delivery. */
    private static final String EVENT = "cmsEvent";

    /** Log field: the content type the delivery was about. */
    private static final String CONTENT_TYPE = "contentTypeUid";

    /** Log field: how many overrides were stored. */
    private static final String FLAG_COUNT = "flagCount";

    private static final String KEY_EVENT = "event";
    private static final String KEY_DATA = "data";
    private static final String KEY_CONTENT_TYPE_UID = "content_type_uid";
    private static final String KEY_CONTENT_TYPE = "content_type";
    private static final String KEY_UID = "uid";

    /** Event fragments that mean "this configuration no longer applies". */
    private static final String EVENT_DELETE = "delete";
    private static final String EVENT_UNPUBLISH = "unpublish";

    private final BrandFlagsContentPort contentPort;
    private final BrandFlagsRepositoryPort repository;
    private final BrandFlagsCachePort cache;
    private final BrandFlagsProperties properties;
    private final LandingProperties landingProperties;

    /**
     * @param contentPort    reads the authored entry from the CMS
     * @param repository     the durable store every instance reads
     * @param cache          this instance's copy, evicted so the change shows here immediately
     * @param properties     supplies the content type this service acts on, and the seed brand list
     * @param landingProperties supplies the {@code landing.feature-flags} snapshot written when seeding
     */
    public BrandFlagsUseCases(BrandFlagsContentPort contentPort,
                              BrandFlagsRepositoryPort repository,
                              BrandFlagsCachePort cache,
                              BrandFlagsProperties properties,
                              LandingProperties landingProperties) {
        this.contentPort = contentPort;
        this.repository = repository;
        this.cache = cache;
        this.properties = properties;
        this.landingProperties = landingProperties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public BrandFlagsSyncResult apply(String brand, Map<String, Object> payload) {
        if (brand == null || brand.isBlank()) {
            return ignored(null, "Delivery carried no brand; x-brand-id is required");
        }
        String normalized = brand.trim().toUpperCase(Locale.ROOT);
        if (!properties.enabled()) {
            return ignored(normalized, "Per-brand feature flags are switched off on this service");
        }
        String contentTypeUid = contentTypeUid(payload);
        if (!properties.contentType().equalsIgnoreCase(contentTypeUid)) {
            // Not ours. Answered as a success on purpose: Contentstack retries every non-2xx, so a
            // stack configured to notify us about everything would otherwise retry forever.
            return ignored(normalized, "Content type '" + contentTypeUid + "' is not the flag entry");
        }
        return isRemoval(payload)
                ? remove(normalized, payload)
                : store(normalized, payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void seedDefaults() {
        for (String brand : properties.seedBrands()) {
            seedIfMissing(brand);
        }
    }

    private void seedIfMissing(String brand) {
        if (brand == null || brand.isBlank()) {
            return;
        }
        String normalized = brand.trim().toUpperCase(Locale.ROOT);
        if (repository.findByBrand(normalized).isPresent()) {
            return;
        }
        BrandFlags defaults = new BrandFlags(normalized, landingProperties.featureFlags(), Instant.now(), null);
        if (repository.saveDefaults(defaults)) {
            LogEvents.at(log, LogEvents.BRAND_FLAGS_SEEDED)
                    .addKeyValue(LogFields.BRAND, normalized)
                    .addKeyValue(FLAG_COUNT, defaults.flags().size())
                    .log("Brand had no flag document; seeded one with the configured defaults");
        } else {
            LogEvents.at(log, LogEvents.BRAND_FLAGS_SEED_FAILED)
                    .addKeyValue(LogFields.BRAND, normalized)
                    .log("Brand had no flag document, and the default snapshot could not be written");
        }
    }

    private BrandFlagsSyncResult store(String brand, Map<String, Object> payload) {
        Optional<BrandFlags> authored = contentPort.fetchForBrand(brand);
        if (authored.isEmpty()) {
            // Nothing usable came back — an unreachable CMS, no entry for this brand, or an entry with
            // no recognised flag. Leaving the stored configuration alone is the safe answer: writing
            // an empty document would silently reset the brand to its defaults.
            return syncFailed(brand, payload,
                    "No usable flag entry was read; the stored configuration is unchanged");
        }
        BrandFlags flags = authored.get();
        if (!repository.save(flags)) {
            return syncFailed(brand, payload, "The flag store rejected the write");
        }
        cache.evict(brand);
        LogEvents.at(log, LogEvents.BRAND_FLAGS_SYNCED)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(EVENT, stringValue(payload, KEY_EVENT))
                .addKeyValue(FLAG_COUNT, flags.flags().size())
                .log("Per-brand feature flags published and stored");
        return new BrandFlagsSyncResult(true, BrandFlagsSyncResult.ACTION_UPSERT, brand,
                "Stored " + flags.flags().size() + " flag override(s)");
    }

    private BrandFlagsSyncResult remove(String brand, Map<String, Object> payload) {
        if (!repository.delete(brand)) {
            return syncFailed(brand, payload, "The flag store rejected the removal");
        }
        cache.evict(brand);
        LogEvents.at(log, LogEvents.BRAND_FLAGS_SYNCED)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(EVENT, stringValue(payload, KEY_EVENT))
                .log("Per-brand feature flags removed; the brand falls back to configured defaults");
        return new BrandFlagsSyncResult(true, BrandFlagsSyncResult.ACTION_DELETE, brand,
                "Removed the brand's overrides; configured defaults now apply");
    }

    private BrandFlagsSyncResult ignored(String brand, String reason) {
        LogEvents.at(log, LogEvents.BRAND_FLAGS_SYNC_IGNORED)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(LogFields.REASON, reason)
                .log("Contentstack webhook ignored");
        return BrandFlagsSyncResult.ignored(brand, reason);
    }

    private BrandFlagsSyncResult syncFailed(String brand, Map<String, Object> payload, String reason) {
        LogEvents.at(log, LogEvents.BRAND_FLAGS_SYNC_FAILED)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(EVENT, stringValue(payload, KEY_EVENT))
                .addKeyValue(CONTENT_TYPE, contentTypeUid(payload))
                .addKeyValue(LogFields.REASON, reason)
                .log("Contentstack webhook could not be applied");
        return new BrandFlagsSyncResult(false, BrandFlagsSyncResult.ACTION_IGNORED, brand, reason);
    }

    private boolean isRemoval(Map<String, Object> payload) {
        String event = stringValue(payload, KEY_EVENT);
        if (event == null) {
            return false;
        }
        String normalized = event.toLowerCase(Locale.ROOT);
        return normalized.contains(EVENT_DELETE) || normalized.contains(EVENT_UNPUBLISH);
    }

    /**
     * Reads the content type from any of the three positions Contentstack has used for it: top level,
     * inside {@code data}, or as {@code data.content_type.uid}.
     */
    private String contentTypeUid(Map<String, Object> payload) {
        String direct = stringValue(payload, KEY_CONTENT_TYPE_UID);
        if (direct != null) {
            return direct;
        }
        Map<String, Object> data = mapValue(payload, KEY_DATA);
        if (data == null) {
            return null;
        }
        String nested = stringValue(data, KEY_CONTENT_TYPE_UID);
        if (nested != null) {
            return nested;
        }
        Map<String, Object> contentType = mapValue(data, KEY_CONTENT_TYPE);
        return contentType == null ? null : stringValue(contentType, KEY_UID);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String stringValue(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }
}
