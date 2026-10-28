package com.liverpool.ms_clp_blp.landing;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.LandingProperties.EagerResolution;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LandingProperties} binding defaults and normalisation. The eager-resolution
 * defaults are pinned here because they are a deliberate product decision — guests get the page
 * inlined in one response, logged-in users get everything lazy — that nothing else in the suite
 * would catch if a future edit collapsed them back to a single number.
 */
class LandingPropertiesTest {

    @Test
    void eagerResolutionDefaults_areLazyForLoggedAndEagerForGuest() {
        LandingProperties properties = new LandingProperties(Map.of(), Map.of(), null);

        assertThat(properties.loggedEagerOffset()).isZero();
        assertThat(properties.guestEagerOffset()).isEqualTo(100);
    }

    @Test
    void eagerResolution_negativeOffsets_clampToZero() {
        EagerResolution resolution = new EagerResolution(-5, -1);

        assertThat(resolution.loggedOffset()).isZero();
        assertThat(resolution.guestOffset()).isZero();
    }

    @Test
    void eagerResolution_configuredOffsets_areKeptIndependently() {
        LandingProperties properties =
                new LandingProperties(Map.of(), Map.of(), new EagerResolution(2, 40));

        assertThat(properties.loggedEagerOffset()).isEqualTo(2);
        assertThat(properties.guestEagerOffset()).isEqualTo(40);
    }

    @Test
    void nullMaps_normaliseToEmpty() {
        LandingProperties properties = new LandingProperties(null, null, null);

        assertThat(properties.featureFlags()).isEmpty();
        assertThat(properties.blocks()).isEmpty();
    }
}
