package com.liverpool.ms_clp_blp.landing.adapters.featureflag;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsDefaultsPort;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsLookupPort;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Resolves a feature flag for one brand, preferring that brand's published overrides and falling back
 * to the service-wide configuration.
 *
 * <p>The order is:</p>
 * <ol>
 *   <li>the brand's published override, via {@link BrandFlagsLookupPort} (a per-instance copy of the
 *       shared store);</li>
 *   <li>the brand's packaged baseline, via {@link BrandFlagsDefaultsPort} — shipped in the image, so
 *       it survives the store being unreachable;</li>
 *   <li>{@code landing.feature-flags.*} from the YAML, i.e. the {@code FF_*} environment variables;</li>
 *   <li>{@code false} for a flag nobody declares — a missing flag disables its block rather than
 *       enabling it by accident.</li>
 * </ol>
 *
 * <p>The middle layer is what keeps an outage from being brand-blind. The YAML is service-wide, so
 * without it a store failure would collapse all thirteen brands onto one set of values; with it, a
 * brand whose baseline genuinely differs keeps that baseline no matter what is reachable.</p>
 *
 * <p><strong>Overrides are partial.</strong> A brand declares only the flags it wants to differ on;
 * everything else falls to the YAML. That is what makes a brand with no document behave exactly as it
 * did before per-brand configuration existed, and what makes this safe to roll out one brand at a
 * time. It is also why the lookup returns an {@link Optional} rather than a boolean: "this brand says
 * false" and "this brand says nothing" are different answers.</p>
 *
 * <p>The evaluated line carries {@code brand} and {@code source} because with per-instance caching the
 * only way to explain "why did this request see the old value" is to know which layer answered.</p>
 */
@Component
public class FeatureFlagAdapter implements FeatureFlagPort {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagAdapter.class);

    /** Log field: the flag's evaluated value. */
    private static final String ENABLED = "enabled";

    /** Log field: which layer of the chain produced the value. */
    private static final String SOURCE = "flagSource";

    private static final String SOURCE_BRAND = "brand";
    private static final String SOURCE_BRAND_BASELINE = "brand-baseline";
    private static final String SOURCE_DEFAULTS = "defaults";

    private final LandingProperties landingProperties;
    private final BrandFlagsLookupPort brandFlags;
    private final BrandFlagsDefaultsPort brandBaseline;

    /**
     * @param landingProperties landing configuration containing the {@code feature-flags} map
     * @param brandFlags     per-brand published overrides, cached per instance
     * @param brandBaseline  per-brand baselines packaged with the artefact
     */
    public FeatureFlagAdapter(LandingProperties landingProperties,
                              BrandFlagsLookupPort brandFlags,
                              BrandFlagsDefaultsPort brandBaseline) {
        this.landingProperties = landingProperties;
        this.brandFlags = brandFlags;
        this.brandBaseline = brandBaseline;
    }

    /**
     * {@inheritDoc}
     *
     * @param flagId the flag identifier (e.g. {@code products-list-salesforce})
     * @param brand  the brand whose page is being composed; {@code null} falls straight to defaults
     */
    @Override
    public boolean isEnabled(String flagId, String brand) {
        Optional<Boolean> published = brandFlags.lookup(flagId, brand);
        Optional<Boolean> baseline = published.isPresent()
                ? Optional.empty()
                : brandBaseline.lookup(flagId, brand);
        boolean enabled = published
                .or(() -> baseline)
                .orElseGet(() -> configuredDefault(flagId));
        LogEvents.at(log, LogEvents.FEATURE_FLAG_EVALUATED)
                .addKeyValue(LogFields.FLAG_ID, flagId)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(SOURCE, sourceOf(published, baseline))
                .addKeyValue(ENABLED, enabled)
                .log("Feature flag evaluated");
        return enabled;
    }

    /**
     * Which layer answered. Without this an operator cannot tell a published override from a packaged
     * baseline, which is exactly the question asked when a brand behaves unexpectedly during an
     * outage.
     */
    private static String sourceOf(Optional<Boolean> published, Optional<Boolean> baseline) {
        if (published.isPresent()) {
            return SOURCE_BRAND;
        }
        return baseline.isPresent() ? SOURCE_BRAND_BASELINE : SOURCE_DEFAULTS;
    }

    private boolean configuredDefault(String flagId) {
        return Boolean.TRUE.equals(landingProperties.featureFlags().get(flagId));
    }
}
