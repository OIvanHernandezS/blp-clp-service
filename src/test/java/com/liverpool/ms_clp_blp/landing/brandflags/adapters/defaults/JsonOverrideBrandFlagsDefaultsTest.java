package com.liverpool.ms_clp_blp.landing.brandflags.adapters.defaults;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.brandflags.BrandFlagsProperties;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of the override variable, because every failure mode here is silent: a key that
 * parses wrong does not throw, it just means a published intention never takes effect.
 */
class JsonOverrideBrandFlagsDefaultsTest {

    /** The documented shape: one flat object keyed {@code <flag>_<BRAND>}. */
    @Test
    void readsOverridesKeyedByFlagAndBrand() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"products-list-salesforce-v2_LP": false, "jewel_GAP": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, "LP")).contains(false);
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).contains(false);
    }

    /** An override can turn something on as well as off. */
    @Test
    void readsAnOverrideInEitherDirection() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"vertex_BR": true, "jewel_BR": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.VERTEX, "BR")).contains(true);
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "BR")).contains(false);
    }

    /**
     * The brand is the segment after the <em>last</em> underscore, so a flag id carrying its own
     * separator still parses. Splitting on the first underscore would silently drop these.
     */
    @Test
    void takesTheBrandFromTheLastUnderscore() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"products_list_salesforce_GAP": false, "carousel-restricted-products_GAP": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE, "GAP")).contains(false);
        assertThat(defaults.lookup(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS, "GAP")).contains(false);
    }

    /** The brand travels in a header, so its case is whatever the caller sent. */
    @Test
    void matchesTheBrandRegardlessOfCase() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"jewel_gap": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).contains(false);
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "gap")).contains(false);
    }

    /**
     * The core of the design: only overrides are listed. A flag nobody names must read as absent so
     * the caller falls through to {@code landing.feature-flags} — never as {@code false}.
     */
    @Test
    void reportsAnythingNotOverriddenAsAbsent() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"jewel_GAP": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.VERTEX, "GAP")).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "LP")).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, null)).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "  ")).isEmpty();
    }

    /** No overrides at all is the normal state, not an error. */
    @Test
    void toleratesAnEmptyVariable() {
        assertThat(defaults("").lookup(LandingFeatureFlags.JEWEL, "GAP")).isEmpty();
        assertThat(defaults(null).lookup(LandingFeatureFlags.JEWEL, "GAP")).isEmpty();
        assertThat(defaults("{}").lookup(LandingFeatureFlags.JEWEL, "GAP")).isEmpty();
    }

    /**
     * The load-bearing case. A stray character in one environment variable must not stop the service
     * from starting — every brand simply falls back to the service-wide values.
     */
    @Test
    void ignoresMalformedJsonInsteadOfFailingStartup() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("{ this is not json");

        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).isEmpty();
    }

    /** A key cannot invent a flag; unknown ids are dropped, not stored under a made-up name. */
    @Test
    void ignoresKeysThatNameNoKnownFlag() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"not-a-flag_GAP": false, "jewel_GAP": false, "jewel": false, "_GAP": false}""");

        assertThat(defaults.lookup("not-a-flag", "GAP")).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).contains(false);
    }

    /**
     * A value that is not a decision is dropped rather than guessed at, so the flag falls through
     * instead of resolving to something nobody wrote.
     */
    @Test
    void ignoresValuesThatAreNotBooleans() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"jewel_GAP": 1, "vertex_GAP": "maybe", "products-list-salesforce-v2_GAP": "false"}""");

        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.VERTEX, "GAP")).isEmpty();
        assertThat(defaults.lookup(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE_V2, "GAP")).contains(false);
    }

    /** One malformed key must not discard the ones around it. */
    @Test
    void keepsTheGoodKeysWhenOneIsWrong() {
        JsonOverrideBrandFlagsDefaults defaults = defaults("""
                {"typo_flag_GAP": false, "jewel_GAP": false, "vertex_LP": false}""");

        assertThat(defaults.lookup(LandingFeatureFlags.JEWEL, "GAP")).contains(false);
        assertThat(defaults.lookup(LandingFeatureFlags.VERTEX, "LP")).contains(false);
    }

    private static JsonOverrideBrandFlagsDefaults defaults(String json) {
        return new JsonOverrideBrandFlagsDefaults(
                new BrandFlagsProperties(true, "ms_clp_blp_flags", "es-mx", "ms_clp_blp_configuration",
                        Duration.ofMinutes(3), List.of(), json),
                new ObjectMapper());
    }
}
