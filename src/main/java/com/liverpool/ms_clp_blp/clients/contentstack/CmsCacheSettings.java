package com.liverpool.ms_clp_blp.clients.contentstack;

import java.time.Duration;

/**
 * The invariants every CMS read cache must satisfy, checked once here instead of three times.
 *
 * <p>Every slice that caches Contentstack reads binds its own {@code @ConfigurationProperties} record
 * under its own prefix and calls this from its compact constructor ({@code cms.landing-cache} today).
 * It lives in {@code clients} so any slice can use it, since ArchUnit forbids a slice depending on
 * another slice's types.</p>
 *
 * <p>These caches matter more than they used to. Until ADR-053 the content-service proxy held a Redis
 * layer invalidated by Contentstack webhooks, so a per-instance Caffeine cache was one tier among
 * several. It is now the only cache in front of Contentstack, which makes {@code expireAfterWrite} the
 * worst-case delay before a published edit becomes visible.</p>
 */
public final class CmsCacheSettings {

    private CmsCacheSettings() {
    }

    /**
     * Fails startup on a configuration that would quietly stop being a cache rather than fail loudly.
     *
     * <p>{@code expireAfterWrite} must exceed {@code refreshAfterWrite}: Caffeine only refreshes an
     * entry that is still present, so an expiry at or below the refresh interval means the background
     * refresh never runs and every read past that interval blocks on Contentstack instead.</p>
     *
     * @param refreshAfterWrite background refresh interval
     * @param expireAfterWrite  hard expiry
     * @param maximumSize       entry ceiling
     * @param prefix            the configuration prefix, so the message names the key to fix
     * @throws IllegalArgumentException when the settings cannot work as intended
     */
    public static void validate(Duration refreshAfterWrite, Duration expireAfterWrite,
            long maximumSize, String prefix) {
        if (refreshAfterWrite == null || refreshAfterWrite.isZero() || refreshAfterWrite.isNegative()) {
            throw new IllegalArgumentException(prefix + ".refresh-after-write must be positive");
        }
        if (expireAfterWrite == null || expireAfterWrite.compareTo(refreshAfterWrite) <= 0) {
            throw new IllegalArgumentException(
                    prefix + ".expire-after-write must be greater than refresh-after-write");
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException(prefix + ".maximum-size must be positive");
        }
    }
}
