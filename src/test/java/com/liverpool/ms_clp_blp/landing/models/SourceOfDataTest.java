package com.liverpool.ms_clp_blp.landing.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of the Contentstack {@code source_of_data} field. */
class SourceOfDataTest {

    @Test
    void from_nullAttribute_isNone() {
        assertThat(SourceOfData.from(null)).isEqualTo(SourceOfData.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void from_blankAttribute_isNone(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.NONE);
    }

    @Test
    void from_nonStringAttribute_isNone() {
        assertThat(SourceOfData.from(42)).isEqualTo(SourceOfData.NONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jewel", "JEWEL", " Jewel "})
    void from_jewel_isCaseAndWhitespaceInsensitive(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.JEWEL);
    }

    @Test
    void from_salesforce_isSalesforce() {
        assertThat(SourceOfData.from("salesforce")).isEqualTo(SourceOfData.SALESFORCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vertex", "VERTEX", " Vertex "})
    void from_vertex_isCaseAndWhitespaceInsensitive(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.VERTEX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"salesforce_v2", "SALESFORCE_V2", " Salesforce_V2 "})
    void from_salesforceV2_isCaseAndWhitespaceInsensitive(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.SALESFORCE_V2);
    }

    /** V2 is its own provider: it must never fall into V1's routing, which shares the {@code salesforce} prefix. */
    @Test
    void from_salesforceV2_isNotSalesforceV1() {
        assertThat(SourceOfData.from("salesforce_v2")).isNotEqualTo(SourceOfData.SALESFORCE);
        assertThat(SourceOfData.SALESFORCE_V2.token()).isEqualTo("salesforce_v2");
    }

    /** Legacy GroupBy-tagged carousels alias to the Vertex provider (ADR-055, supersedes ADR-014). */
    @ParameterizedTest
    @ValueSource(strings = {"groupby", "GROUPBY", " GroupBy "})
    void from_groupby_isCaseAndWhitespaceInsensitive(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.GROUPBY);
    }

    @Test
    void groupby_token_isGroupby() {
        assertThat(SourceOfData.GROUPBY.token()).isEqualTo("groupby");
    }

    /** Sources present in the CMS that ms-clp-blp does not implement (ADR-015). */
    @ParameterizedTest
    @ValueSource(strings = {"lob", "recently_viewed", "something_new"})
    void from_unimplementedSource_isUnsupported(String raw) {
        assertThat(SourceOfData.from(raw)).isEqualTo(SourceOfData.UNSUPPORTED);
    }

    /** UNSUPPORTED is synthetic — it must never be produced by matching a literal token. */
    @Test
    void from_neverMatchesSyntheticValuesByToken() {
        assertThat(SourceOfData.NONE.token()).isNull();
        assertThat(SourceOfData.UNSUPPORTED.token()).isNull();
    }
}
