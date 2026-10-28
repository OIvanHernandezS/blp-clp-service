package com.liverpool.ms_clp_blp.landing.models;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class LandingFeatureFlagsTest {

    /**
     * The exact ids pinned here are what an operator types into the Mongo document and into the
     * {@code FF_*} environment variables, so a rename that looks harmless in Java silently stops
     * honouring every flag already published for the thirteen brands.
     */
    @ParameterizedTest
    @CsvSource({
            "JEWEL,       jewel",
            "VERTEX,      vertex",
            "GROUPBY,     vertex",
            "SALESFORCE,  products-list-salesforce",
            "SALESFORCE_V2, products-list-salesforce-v2",
            "NONE,        products-list-salesforce"})
    void productsListFlagId_mapsEachSourceToItsProviderFlag(SourceOfData source, String expected) {
        assertThat(LandingFeatureFlags.productsListFlagId(source)).isEqualTo(expected);
    }

    /** A source this service does not implement is never routed, so no kill switch governs it. */
    @ParameterizedTest
    @EnumSource(value = SourceOfData.class, names = "UNSUPPORTED")
    void productsListFlagId_unsupportedSource_hasNoFlag(SourceOfData source) {
        assertThat(LandingFeatureFlags.productsListFlagId(source)).isNull();
    }

    /**
     * {@link LandingFeatureFlags#ALL} is the allow-list applied to CMS-authored configuration, so an id
     * outside it could never be published per brand — it would be a kill switch nobody can reach.
     */
    @ParameterizedTest
    @EnumSource(SourceOfData.class)
    void productsListFlagId_everyMappedId_isPublishable(SourceOfData source) {
        String flagId = LandingFeatureFlags.productsListFlagId(source);

        assertThat(flagId == null || LandingFeatureFlags.ALL.contains(flagId)).isTrue();
    }

    @org.junit.jupiter.api.Test
    void carouselWideFlags_arePublishable() {
        assertThat(LandingFeatureFlags.ALL)
                .contains(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE, LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS);
    }
}
