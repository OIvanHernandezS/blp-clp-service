package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parsing of a Vertex block's CMS attributes (Rule 19 reference: {@code product_list-vertex.strategy.ts}
 * reads {@code block.products_data.category} — a nested attribute, unlike Salesforce's/Jewel's flat
 * ones).
 */
class VertexSettingsTest {

    @Test
    void source_isVertex() {
        assertThat(new VertexSettings(null, null, null).source()).isEqualTo(SourceOfData.VERTEX);
    }

    @Test
    void from_nullAttributes_everyFieldIsNull() {
        VertexSettings settings = VertexSettings.from(null);

        assertThat(settings.categoryId()).isNull();
        assertThat(settings.minOfProducts()).isNull();
        assertThat(settings.maxOfProducts()).isNull();
    }

    @Test
    void from_nestedProductsDataCategory_isRead() {
        VertexSettings settings = VertexSettings.from(Map.of(
                "products_data", Map.of("category", "cat-123")));

        assertThat(settings.categoryId()).isEqualTo("cat-123");
    }

    @Test
    void from_missingProductsData_categoryIsNull() {
        VertexSettings settings = VertexSettings.from(Map.of("min_of_products", "6"));

        assertThat(settings.categoryId()).isNull();
        assertThat(settings.minOfProducts()).isEqualTo(6);
    }

    @Test
    void from_productsDataNotAMap_categoryIsNull() {
        VertexSettings settings = VertexSettings.from(Map.of("products_data", "not-a-map"));

        assertThat(settings.categoryId()).isNull();
    }

    @Test
    void from_productsDataWithoutCategoryKey_categoryIsNull() {
        VertexSettings settings = VertexSettings.from(Map.of(
                "products_data", Map.of("otherField", "value")));

        assertThat(settings.categoryId()).isNull();
    }

    @Test
    void from_minAndMaxOfProducts_areParsed() {
        VertexSettings settings = VertexSettings.from(Map.of(
                "products_data", Map.of("category", "cat-1"),
                "min_of_products", "6",
                "max_of_products", "20"));

        assertThat(settings.minOfProducts()).isEqualTo(6);
        assertThat(settings.maxOfProducts()).isEqualTo(20);
    }
}
