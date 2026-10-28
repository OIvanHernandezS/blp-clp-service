package com.liverpool.ms_clp_blp.landing.adapters.cache;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsCacheSettings;

/**
 * Caffeine tuning for the landing definition cache.
 *
 * @param enabled           whether the cache is consulted at all
 * @param refreshAfterWrite background refresh interval
 * @param expireAfterWrite  hard expiry; also the worst-case delay before a CMS edit becomes visible
 * @param maximumSize       entry ceiling
 */
@ConfigurationProperties(prefix = "cms.landing-cache")
public record LandingContentCacheProperties(
        boolean enabled,
        Duration refreshAfterWrite,
        Duration expireAfterWrite,
        long maximumSize) {

    public LandingContentCacheProperties {
        CmsCacheSettings.validate(refreshAfterWrite, expireAfterWrite, maximumSize, "cms.landing-cache");
    }
}
