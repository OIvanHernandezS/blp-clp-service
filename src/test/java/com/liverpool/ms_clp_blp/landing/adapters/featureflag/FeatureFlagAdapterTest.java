package com.liverpool.ms_clp_blp.landing.adapters.featureflag;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsDefaultsPort;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsLookupPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the precedence chain. Each case is a different way an operator can be surprised: a brand
 * override that does not win, a partial override that wipes the rest, or an unreachable store that
 * turns every block off.
 */
class FeatureFlagAdapterTest {

    private static final String BRAND = "GAP";

    /** The whole point: a brand's value beats the service-wide default. */
    @Test
    void aBrandOverrideWinsOverTheConfiguredDefault() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, true),
                (flagId, brand) -> Optional.of(false));

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
    }

    /** And in the other direction — an override that turns something on must also win. */
    @Test
    void aBrandOverrideCanAlsoEnableWhatTheDefaultDisables() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.VERTEX, false),
                (flagId, brand) -> Optional.of(true));

        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isTrue();
    }

    /**
     * The case that makes partial overrides safe: a brand declaring only {@code jewel} must not have
     * every other flag silently read as false.
     */
    @Test
    void fallsBackToTheDefaultForAFlagTheBrandDoesNotDeclare() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, false, LandingFeatureFlags.VERTEX, true),
                (flagId, brand) -> LandingFeatureFlags.JEWEL.equals(flagId)
                        ? Optional.of(true)
                        : Optional.empty());

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isTrue();
    }

    /**
     * The degradation contract. An unreachable store looks exactly like a brand with no overrides, so
     * the page is composed from the YAML rather than with everything switched off.
     */
    @Test
    void behavesExactlyAsBeforeWhenTheStoreHasNothingToSay() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, true, LandingFeatureFlags.VERTEX, false),
                (flagId, brand) -> Optional.empty());

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isFalse();
    }

    /** No session resolved: there is no brand to key on, so the defaults apply. */
    @Test
    void usesTheDefaultsWhenThereIsNoBrand() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, true),
                (flagId, brand) -> brand == null ? Optional.empty() : Optional.of(false));

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, null)).isTrue();
    }

    /** Unknown flag stays false — a typo must disable a block, never enable one. */
    @Test
    void anUndeclaredFlagIsDisabled() {
        FeatureFlagAdapter adapter = adapter(Map.of(), (flagId, brand) -> Optional.empty());

        assertThat(adapter.isEnabled("not-a-flag", BRAND)).isFalse();
    }


    /**
     * The reason the packaged layer exists. With the store unreachable, a brand whose baseline is
     * shipped in the image keeps it instead of collapsing onto the service-wide value — which for
     * `vertex` here would have been the opposite answer.
     */
    @Test
    void thePackagedBaselineAnswersWhenTheStoreHasNothing() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.VERTEX, true),
                (flagId, brand) -> Optional.empty(),
                (flagId, brand) -> Optional.of(false));

        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isFalse();
    }

    /** A published override is more current than a packaged baseline, so it wins. */
    @Test
    void aPublishedOverrideBeatsThePackagedBaseline() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.VERTEX, false),
                (flagId, brand) -> Optional.of(true),
                (flagId, brand) -> Optional.of(false));

        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isTrue();
    }

    /** A flag the packaged file does not mention still falls through to the service-wide value. */
    @Test
    void fallsPastAPackagedBaselineThatDoesNotDeclareTheFlag() {
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, true),
                (flagId, brand) -> Optional.empty(),
                (flagId, brand) -> LandingFeatureFlags.VERTEX.equals(flagId)
                        ? Optional.of(false)
                        : Optional.empty());

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isTrue();
        assertThat(adapter.isEnabled(LandingFeatureFlags.VERTEX, BRAND)).isFalse();
    }

    /** The store is authoritative when it answers, so the packaged layer must not even be consulted. */
    @Test
    void doesNotConsultThePackagedBaselineWhenTheStoreAnswered() {
        boolean[] consulted = {false};
        FeatureFlagAdapter adapter = adapter(
                Map.of(LandingFeatureFlags.JEWEL, true),
                (flagId, brand) -> Optional.of(false),
                (flagId, brand) -> {
                    consulted[0] = true;
                    return Optional.of(true);
                });

        assertThat(adapter.isEnabled(LandingFeatureFlags.JEWEL, BRAND)).isFalse();
        assertThat(consulted[0]).isFalse();
    }

    private static FeatureFlagAdapter adapter(Map<String, Boolean> defaults, BrandFlagsLookupPort lookup) {
        return adapter(defaults, lookup, (flagId, brand) -> Optional.empty());
    }

    private static FeatureFlagAdapter adapter(Map<String, Boolean> defaults,
                                              BrandFlagsLookupPort lookup,
                                              BrandFlagsDefaultsPort baseline) {
        return new FeatureFlagAdapter(new LandingProperties(defaults, Map.of(), null), lookup, baseline);
    }
}
