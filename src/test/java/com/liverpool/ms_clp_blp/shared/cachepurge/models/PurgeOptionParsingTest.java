package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the two operator-supplied options.
 *
 * <p>The rejection cases carry the weight. Silently defaulting a typo is the dangerous behaviour
 * here: {@code ?network=prod} would purge staging while looking like a production purge that did
 * nothing, and {@code ?action=invalidat} would quietly evict instead of invalidating. Both must be a
 * 400.</p>
 */
class PurgeOptionParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {"invalidate", "INVALIDATE", "Invalidate", "  invalidate  "})
    void action_isCaseAndWhitespaceInsensitive(String value) {
        assertThat(PurgeAction.parse(value, PurgeAction.DELETE)).isEqualTo(PurgeAction.INVALIDATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staging", "STAGING", "  Staging "})
    void network_isCaseAndWhitespaceInsensitive(String value) {
        assertThat(PurgeNetwork.parse(value, PurgeNetwork.PRODUCTION)).isEqualTo(PurgeNetwork.STAGING);
    }

    @Test
    void absentValues_fallBackToTheConfiguredDefault() {
        assertThat(PurgeAction.parse(null, PurgeAction.DELETE)).isEqualTo(PurgeAction.DELETE);
        assertThat(PurgeAction.parse("  ", PurgeAction.DELETE)).isEqualTo(PurgeAction.DELETE);
        assertThat(PurgeNetwork.parse(null, PurgeNetwork.STAGING)).isEqualTo(PurgeNetwork.STAGING);
        assertThat(PurgeNetwork.parse("", PurgeNetwork.STAGING)).isEqualTo(PurgeNetwork.STAGING);
    }

    @Test
    void anUnknownActionIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> PurgeAction.parse("invalidat", PurgeAction.INVALIDATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownNetworkIsRejectedRatherThanDefaulted() {
        assertThatThrownBy(() -> PurgeNetwork.parse("prod", PurgeNetwork.PRODUCTION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** These strings are path segments on the Akamai URL, so their casing is load-bearing. */
    @Test
    void wireValuesAreTheLowercaseSegmentsAkamaiExpects() {
        assertThat(PurgeAction.INVALIDATE.wireValue()).isEqualTo("invalidate");
        assertThat(PurgeAction.DELETE.wireValue()).isEqualTo("delete");
        assertThat(PurgeNetwork.STAGING.wireValue()).isEqualTo("staging");
        assertThat(PurgeNetwork.PRODUCTION.wireValue()).isEqualTo("production");
        assertThat(PurgeTargetKind.URL.wireValue()).isEqualTo("url");
        assertThat(PurgeTargetKind.TAG.wireValue()).isEqualTo("tag");
    }
}
