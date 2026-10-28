package com.liverpool.ms_clp_blp.landing.adapters.featureflag;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.LandingSettings;
import com.liverpool.ms_clp_blp.landing.ports.BrandFlagsLookupPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two-layer chain: a brand's value from the store, otherwise the configured fallback.
 */
class RuntimeSettingsAdapterTest {

    private static final String BRAND = "LP";
    private static final int FALLBACK = 1;

    @Test
    void aBrandOverrideWinsOverTheFallback() {
        RuntimeSettingsAdapter adapter = new RuntimeSettingsAdapter(withSetting(Optional.of(3)));

        assertThat(adapter.intValue(LandingSettings.PRODUCTS_LIST_MAX_IMAGES, BRAND, FALLBACK)).isEqualTo(3);
    }

    /** Zero is a value, not an absence: it must win, so an operator can lift the limit for one brand. */
    @Test
    void aZeroOverrideStillWins() {
        RuntimeSettingsAdapter adapter = new RuntimeSettingsAdapter(withSetting(Optional.of(0)));

        assertThat(adapter.intValue(LandingSettings.PRODUCTS_LIST_MAX_IMAGES, BRAND, FALLBACK)).isZero();
    }

    /** No override, an unknown brand, or a store that is down all read as "nothing to say". */
    @Test
    void fallsBackWhenTheBrandDeclaresNothing() {
        RuntimeSettingsAdapter adapter = new RuntimeSettingsAdapter(withSetting(Optional.empty()));

        assertThat(adapter.intValue(LandingSettings.PRODUCTS_LIST_MAX_IMAGES, BRAND, FALLBACK)).isEqualTo(FALLBACK);
        assertThat(adapter.intValue(LandingSettings.PRODUCTS_LIST_MAX_IMAGES, null, FALLBACK)).isEqualTo(FALLBACK);
    }

    /** A lookup that carries no settings at all (the port's default) lands on the fallback. */
    @Test
    void fallsBackWhenTheLookupCarriesNoSettings() {
        RuntimeSettingsAdapter adapter = new RuntimeSettingsAdapter((flagId, brand) -> Optional.of(true));

        assertThat(adapter.intValue(LandingSettings.PRODUCTS_LIST_MAX_IMAGES, BRAND, FALLBACK)).isEqualTo(FALLBACK);
    }

    private static BrandFlagsLookupPort withSetting(Optional<Integer> value) {
        return new BrandFlagsLookupPort() {
            @Override
            public Optional<Boolean> lookup(String flagId, String brand) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> lookupSetting(String settingId, String brand) {
                return value;
            }
        };
    }
}
