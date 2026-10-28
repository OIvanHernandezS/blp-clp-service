package com.liverpool.ms_clp_blp.landing.models;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.liverpool.ms_clp_blp.shared.session.CohortMarkers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConditionalShowHide} — the port of commons' {@code ConditionalShowHideEvaluator}.
 *
 * <p>The cases follow the commons evaluator's own contract table, so a carousel splits cohorts the same
 * way on a landing page as on PDP and PLP: {@code show} keeps the block only for a {@code true} marker,
 * {@code hide} only for a {@code false} one, an unreadable marker always drops, and a malformed rule
 * fails open.</p>
 */
class ConditionalShowHideTest {

    private static final String HEADER = "x-ab-carousel";
    private static final String COOKIE = "ab_carousel";

    // ── reading the rule from the CMS ─────────────────────────────────────────────────────────────

    @Test
    void from_fullGroup_readsEveryField() {
        ConditionalShowHide rule = ConditionalShowHide.from(block(group(true, "show", HEADER, COOKIE)));

        assertThat(rule).isEqualTo(new ConditionalShowHide(true, "show", HEADER, COOKIE));
    }

    /** Legacy templates deliver checkboxes as strings. */
    @Test
    void from_activeAsString_isRead() {
        Map<String, Object> group = group(null, "hide", HEADER, null);
        group.put("active", "true");

        assertThat(ConditionalShowHide.from(block(group)).active()).isTrue();
    }

    @Test
    void from_absentOrMalformedGroup_isNone() {
        assertThat(ConditionalShowHide.from(null)).isEqualTo(ConditionalShowHide.NONE);
        assertThat(ConditionalShowHide.from(Map.of())).isEqualTo(ConditionalShowHide.NONE);
        assertThat(ConditionalShowHide.from(Map.of("conditional_show_hide", "show")))
                .isEqualTo(ConditionalShowHide.NONE);
    }

    /** The legacy value is never compared against — the request's marker is what decides. */
    @Test
    void from_legacyHeaderValue_isIgnored() {
        Map<String, Object> group = group(true, "show", HEADER, null);
        group.put("header_value", "false");

        assertThat(ConditionalShowHide.from(block(group)))
                .isEqualTo(new ConditionalShowHide(true, "show", HEADER, null));
    }

    // ── when the rule applies ─────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"show", "hide", "SHOW", "Hide"})
    void applies_activeWithKnownActionAndAMarkerName_isTrue(String action) {
        assertThat(new ConditionalShowHide(true, action, HEADER, null).applies()).isTrue();
        assertThat(new ConditionalShowHide(true, action, null, COOKIE).applies()).isTrue();
    }

    @Test
    void applies_inactiveOrMalformed_isFalse() {
        assertThat(new ConditionalShowHide(false, "show", HEADER, COOKIE).applies()).isFalse();
        assertThat(new ConditionalShowHide(true, "toggle", HEADER, COOKIE).applies()).isFalse();
        assertThat(new ConditionalShowHide(true, null, HEADER, COOKIE).applies()).isFalse();
        assertThat(new ConditionalShowHide(true, "show", " ", "").applies()).isFalse();
        assertThat(new ConditionalShowHide(true, "show", null, null).applies()).isFalse();
        assertThat(ConditionalShowHide.NONE.applies()).isFalse();
    }

    // ── the cohort decision ───────────────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} rule, marker {1} → {2}")
    @CsvSource({
            "show, true,  KEPT",
            "show, false, CONDITIONAL_SHOW_HIDE_EXCLUDED",
            "hide, true,  CONDITIONAL_SHOW_HIDE_EXCLUDED",
            "hide, false, KEPT"
    })
    void dropReason_followsTheCommonsContractTable(String action, boolean marker, String expected) {
        ConditionalShowHide rule = new ConditionalShowHide(true, action, HEADER, null);

        BlockDropReason reason = rule.dropReason(headers(Map.of(HEADER, marker)));

        assertThat(reason == null ? "KEPT" : reason.name()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"show", "hide"})
    void dropReason_noReadableMarker_dropsWhateverTheAction(String action) {
        ConditionalShowHide rule = new ConditionalShowHide(true, action, HEADER, COOKIE);

        assertThat(rule.dropReason(CohortMarkers.NONE))
                .isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN);
        assertThat(rule.dropReason(null)).isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN);
    }

    @Test
    void dropReason_ruleThatDoesNotApply_keepsTheBlockWithoutAMarker() {
        assertThat(new ConditionalShowHide(false, "show", HEADER, null).dropReason(CohortMarkers.NONE))
                .isNull();
        assertThat(new ConditionalShowHide(true, "toggle", HEADER, null).dropReason(CohortMarkers.NONE))
                .isNull();
    }

    @Test
    void dropReason_cookieOnlyRule_readsTheCookie() {
        ConditionalShowHide rule = new ConditionalShowHide(true, "show", null, COOKIE);

        assertThat(rule.dropReason(new CohortMarkers(Map.of(), Map.of(COOKIE, true)))).isNull();
        assertThat(rule.dropReason(new CohortMarkers(Map.of(), Map.of(COOKIE, false))))
                .isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
    }

    /** A stale cookie must never override a header the client did send. */
    @Test
    void dropReason_bothMarkersSent_headerWins() {
        ConditionalShowHide rule = new ConditionalShowHide(true, "show", HEADER, COOKIE);

        BlockDropReason reason = rule.dropReason(new CohortMarkers(Map.of(HEADER, true), Map.of(COOKIE, false)));

        assertThat(reason).isNull();
    }

    @Test
    void dropReason_headerNamedButNotSent_fallsBackToTheCookie() {
        ConditionalShowHide rule = new ConditionalShowHide(true, "show", HEADER, COOKIE);

        assertThat(rule.dropReason(new CohortMarkers(Map.of(), Map.of(COOKIE, false))))
                .isEqualTo(BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED);
    }

    @Test
    void dropReason_headerNameIsMatchedCaseInsensitively() {
        ConditionalShowHide rule = new ConditionalShowHide(true, "show", "X-AB-Carousel", null);

        assertThat(rule.dropReason(headers(Map.of(HEADER, true)))).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static CohortMarkers headers(Map<String, Boolean> headers) {
        return new CohortMarkers(headers, Map.of());
    }

    private static Map<String, Object> block(Map<String, Object> group) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("uid", "blt-ab");
        block.put("conditional_show_hide", group);
        return block;
    }

    /** Mutable and null-tolerant, like a partly filled CMS group. */
    private static Map<String, Object> group(Boolean active, String action, String header, String cookie) {
        Map<String, Object> group = new HashMap<>();
        group.put("active", active);
        group.put("action", action);
        group.put("header_name", header);
        group.put("cookie_name", cookie);
        return group;
    }
}
