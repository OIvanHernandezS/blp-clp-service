package com.liverpool.ms_clp_blp.landing.brandflags;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime configuration for per-brand feature flags: whether they are consulted at all, where they
 * come from, and how stale an instance's copy may get.
 *
 * <p>{@code enabled} is a genuine kill switch, not a convenience. Turned off, {@link
 * com.liverpool.ms_clp_blp.landing.adapters.featureflag.FeatureFlagAdapter} never touches the store and
 * every flag resolves from {@code landing.feature-flags} exactly as it did before this feature existed —
 * which is both the escape hatch if the store misbehaves in production and the reason local
 * development needs no database (it is off in the {@code dev} profile).</p>
 *
 * <p>Note there is no webhook credential here: {@code POST /webhooks/contentstack} authenticates with
 * the same inbound HTTP Basic credential as every other endpoint (see {@code SecurityConfig}), so
 * there is nothing webhook-specific to configure.</p>
 *
 * @param enabled       whether per-brand overrides are consulted; false means YAML-only
 * @param contentType   Contentstack content type carrying the overrides (e.g. {@code ms_clp_blp_flags})
 * @param locale        locale used to fetch that entry. Flags are not localised, but the proxy's
 *                      route requires a locale segment, so one is fixed here rather than guessed
 * @param collection    MongoDB collection holding one document per brand and profile
 * @param cacheTtl      how long an instance may serve its copy before re-reading the store; this is
 *                      also the worst-case propagation delay to instances that did not get the webhook
 * @param seedBrands    brands seeded with a {@code landing.feature-flags} snapshot at startup when they
 *                      have no document yet; see {@code BrandFlagsSeedRunnerAdapter}
 * @param overrideJson  per-brand overrides as one JSON object keyed {@code <flag>_<BRAND>}, applied
 *                      when the store has nothing to say. Deliberately the exception and not the
 *                      rule: only brands that must differ appear, everything else falls through to
 *                      {@code landing.feature-flags}
 */
@ConfigurationProperties(prefix = "brand-flags")
public record BrandFlagsProperties(
        boolean enabled,
        String contentType,
        String locale,
        String collection,
        Duration cacheTtl,
        List<String> seedBrands,
        String overrideJson) {

    public BrandFlagsProperties {
        seedBrands = seedBrands == null ? List.of() : List.copyOf(seedBrands);
    }
}
