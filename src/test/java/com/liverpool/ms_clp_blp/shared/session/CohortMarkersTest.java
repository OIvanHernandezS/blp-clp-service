package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CohortMarkers} — the tri-state marker reading shared with commons'
 * {@code ConditionalShowHideEvaluator}.
 */
class CohortMarkersTest {

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " True "})
    void parse_trueInAnyCaseAndPadding_isTrue(String value) {
        assertThat(CohortMarkers.parse(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "FALSE", "\tfalse "})
    void parse_falseInAnyCaseAndPadding_isFalse(String value) {
        assertThat(CohortMarkers.parse(value)).isFalse();
    }

    /**
     * Stricter than {@code Boolean.parseBoolean}, which would read each of these as {@code false} and so
     * let a malformed marker pick an experiment arm.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "0", "yes", "on", "truthy", "blt123"})
    void parse_anythingElse_isUnreadable(String value) {
        assertThat(CohortMarkers.parse(value)).isNull();
    }

    @Test
    void header_isMatchedCaseInsensitively() {
        CohortMarkers markers = new CohortMarkers(Map.of("x-ab-carousel", true), Map.of());

        assertThat(markers.header("X-AB-Carousel")).isTrue();
    }

    /** Cookie names are case-sensitive on the wire, as the commons evaluator treats them. */
    @Test
    void cookie_isMatchedExactly() {
        CohortMarkers markers = new CohortMarkers(Map.of(), Map.of("ab_carousel", false));

        assertThat(markers.cookie("ab_carousel")).isFalse();
        assertThat(markers.cookie("AB_CAROUSEL")).isNull();
    }

    @Test
    void lookups_blankOrAbsentName_areUnreadable() {
        CohortMarkers markers = new CohortMarkers(Map.of("x-ab", true), Map.of("ab", true));

        assertThat(markers.header(null)).isNull();
        assertThat(markers.header(" ")).isNull();
        assertThat(markers.cookie(null)).isNull();
        assertThat(markers.cookie("")).isNull();
        assertThat(markers.header("x-other")).isNull();
    }

    @Test
    void construct_nullMaps_readAsNone() {
        assertThat(new CohortMarkers(null, null)).isEqualTo(CohortMarkers.NONE);
    }
}
