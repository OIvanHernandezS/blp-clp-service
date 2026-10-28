package com.liverpool.ms_clp_blp.landing.brandflags.models;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One brand's runtime feature-flag overrides, as authored in the CMS and persisted for every instance
 * to read.
 *
 * <p><strong>Partial by design.</strong> A brand is not required to declare all of the flags: a flag
 * absent from {@link #flags()} falls through to the {@code landing.feature-flags} value in the YAML, so
 * a brand with no overrides behaves exactly as it did before per-brand configuration existed. That is
 * what makes the rollout gradual, and what makes an incomplete CMS entry harmless.</p>
 *
 * @param brand     brand identifier in upper case (e.g. {@code LP}), never blank
 * @param flags     flag id to value; only ids ms-clp-blp knows about, only non-null booleans
 * @param updatedAt when this snapshot was captured from the CMS
 * @param entryUid  the Contentstack entry it came from, carried for traceability in logs
 * @param settings  numeric setting id to value ({@code LandingSettings}), edited by hand in the store;
 *                  partial exactly like {@code flags}
 */
public record BrandFlags(
        String brand,
        Map<String, Boolean> flags,
        Instant updatedAt,
        String entryUid,
        Map<String, Integer> settings) {

    /**
     * A snapshot with no numeric settings — what the CMS and the seed produce, since settings are only
     * ever authored directly in the store.
     */
    public BrandFlags(String brand, Map<String, Boolean> flags, Instant updatedAt, String entryUid) {
        this(brand, flags, updatedAt, entryUid, Map.of());
    }

    public BrandFlags {
        // Null-tolerant on purpose: these values originate in CMS fields an editor may leave empty,
        // and Map.copyOf throws on a null value — the failure mode that already cost a 502 in
        // GlobalDataClient. Nulls are dropped rather than stored, so a flag an editor cleared falls
        // through to the YAML instead of being read as false.
        flags = flags == null ? Map.of() : copyDroppingNulls(flags);
        settings = settings == null ? Map.of() : copyDroppingNulls(settings);
    }

    /**
     * @param flagId the flag to look up
     * @return the brand's override, or {@code null} when this brand does not declare the flag
     */
    public Boolean valueOf(String flagId) {
        return flags.get(flagId);
    }

    /**
     * @param settingId the setting to look up
     * @return the brand's override, or {@code null} when this brand does not declare the setting
     */
    public Integer settingOf(String settingId) {
        return settings.get(settingId);
    }

    private static <V> Map<String, V> copyDroppingNulls(Map<String, V> source) {
        Map<String, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
