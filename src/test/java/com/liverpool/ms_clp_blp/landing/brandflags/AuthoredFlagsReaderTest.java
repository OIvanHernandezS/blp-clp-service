package com.liverpool.ms_clp_blp.landing.brandflags;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place that decides what an authored flag document means, shared by the CMS entry and the
 * packaged per-brand JSON. It is tested directly so the two sources cannot disagree about it.
 */
class AuthoredFlagsReaderTest {

    /** Flags may sit on the document itself… */
    @Test
    void readsFlagsFromTheDocumentRoot() {
        assertThat(AuthoredFlagsReader.read(Map.of("jewel", true)))
                .containsExactly(Map.entry(LandingFeatureFlags.JEWEL, true));
    }

    /** …or inside a group. Both shapes exist in the wild. */
    @Test
    void readsFlagsFromAGroup() {
        assertThat(AuthoredFlagsReader.read(Map.of("flags", Map.of("vertex", false))))
                .containsExactly(Map.entry(LandingFeatureFlags.VERTEX, false));
    }

    /**
     * A Contentstack field uid cannot contain a hyphen, so the CMS can only write
     * {@code products_list_salesforce}. Rejecting it would make the multi-word flags unconfigurable.
     */
    @Test
    void treatsUnderscoresAndHyphensAsTheSameKey() {
        assertThat(AuthoredFlagsReader.read(Map.of("products_list_salesforce", false)))
                .containsExactly(Map.entry(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE, false));
        assertThat(AuthoredFlagsReader.read(Map.of("PRODUCTS-LIST-SALESFORCE", false)))
                .containsExactly(Map.entry(LandingFeatureFlags.PRODUCTS_LIST_SALESFORCE, false));
    }

    /** An author can add any field they like; none of them may become a flag. */
    @Test
    void ignoresKeysThatNameNoKnownFlag() {
        assertThat(AuthoredFlagsReader.read(Map.of(
                "jewel", true, "title", "GAP", "some_other_toggle", true)))
                .containsExactly(Map.entry(LandingFeatureFlags.JEWEL, true));
    }

    /** A hand-authored text or JSON field arrives as a string rather than a boolean. */
    @Test
    void acceptsBooleansSpelledAsStrings() {
        assertThat(AuthoredFlagsReader.read(Map.of("jewel", "FALSE", "vertex", " true ")))
                .containsOnly(
                        Map.entry(LandingFeatureFlags.JEWEL, false),
                        Map.entry(LandingFeatureFlags.VERTEX, true));
    }

    /**
     * Anything that is not a decision is dropped rather than guessed at — and a dropped flag falls
     * through to the next layer instead of reading as {@code false}.
     */
    @Test
    void dropsValuesThatAreNotBooleans() {
        Map<String, Object> document = new HashMap<>();
        document.put("jewel", 1);
        document.put("vertex", "maybe");
        document.put("products-list-salesforce-v2", null);

        assertThat(AuthoredFlagsReader.read(document)).isEmpty();
    }

    @Test
    void toleratesAnAbsentDocument() {
        assertThat(AuthoredFlagsReader.read(null)).isEmpty();
        assertThat(AuthoredFlagsReader.read(Map.of())).isEmpty();
    }

    @Test
    void returnsAnImmutableResult() {
        Map<String, Boolean> flags = AuthoredFlagsReader.read(Map.of("jewel", true));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> flags.put("vertex", false))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ── numeric settings ─────────────────────────────────────────────────────────────────────────

    @Test
    void readsSettingsOfAnyNumericShape() {
        assertThat(AuthoredFlagsReader.readSettings(Map.of("products-list-max-images", 3)))
                .containsEntry("products-list-max-images", 3);
        assertThat(AuthoredFlagsReader.readSettings(Map.of("products-list-max-images", 3L)))
                .containsEntry("products-list-max-images", 3);
        assertThat(AuthoredFlagsReader.readSettings(Map.of("products-list-max-images", 3.0)))
                .containsEntry("products-list-max-images", 3);
        assertThat(AuthoredFlagsReader.readSettings(Map.of("products-list-max-images", " 3 ")))
                .containsEntry("products-list-max-images", 3);
    }

    @Test
    void readsSettingsSpelledWithUnderscores() {
        assertThat(AuthoredFlagsReader.readSettings(Map.of("PRODUCTS_LIST_MAX_IMAGES", 2)))
                .containsEntry("products-list-max-images", 2);
    }

    /** Not a whole number, or not a known id: dropped, so it falls through rather than reading as 0. */
    @Test
    void dropsSettingsThatAreNotWholeNumbersOrUnknown() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("products-list-max-images", 2.5);
        settings.put("unknown-setting", 4);
        assertThat(AuthoredFlagsReader.readSettings(settings)).isEmpty();

        settings.put("products-list-max-images", "three");
        assertThat(AuthoredFlagsReader.readSettings(settings)).isEmpty();

        settings.put("products-list-max-images", null);
        assertThat(AuthoredFlagsReader.readSettings(settings)).isEmpty();

        assertThat(AuthoredFlagsReader.readSettings(null)).isEmpty();
    }
}
