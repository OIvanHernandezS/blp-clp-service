package com.liverpool.ms_clp_blp.landing.models;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The small value types of the landing slice. */
class LandingModelsTest {

    private static final SessionContext SESSION = new SessionContext(false, "LP", "WEB", "es-mx");

    @ParameterizedTest
    @CsvSource(nullValues = "NULL", value = {
            "tienda/esteelauder/catst26617625, catst26617625",
            "/tienda/esteelauder/catst26617625/, catst26617625",
            "catst1, catst1",
            "/, NULL",
            "'', NULL",
            "NULL, NULL"})
    void categoryId_isTheLastNonEmptySegment(String path, String expected) {
        LandingPageQuery query = new LandingPageQuery(PageType.BLP, "es-mx", path, SESSION, null, null, null);

        assertThat(query.categoryId()).isEqualTo(expected);
        assertThat(query.gatewayHeaders()).isEmpty();
    }

    @Test
    void pageType_resolvesItsRouteSegment_andKnowsWhatItStrips() {
        assertThat(PageType.fromRouteSegment(" BLP ")).isEqualTo(PageType.BLP);
        assertThat(PageType.fromRouteSegment("clp")).isEqualTo(PageType.CLP);
        assertThat(PageType.BLP.strippedKeys()).contains("apps_top_content", "apps_products", "apps_article");
        assertThat(PageType.CLP.strippedKeys()).isEmpty();
        assertThat(PageType.CLP.contentTypeUid()).isEqualTo("page_clp");
        assertThatThrownBy(() -> PageType.fromRouteSegment(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pageSection_namesItsResponseKeyAndSources() {
        assertThat(PageSection.BLOCKS.responseKey()).isEqualTo("blocks");
        assertThat(PageSection.BLOCKS.sourceKeys()).containsExactly("layout", "blocks");
        assertThat(PageSection.TOP_CONTENT.sourceKeys()).containsExactly("top_layout", "top_content");
    }

    @Test
    void definitionAndPage_areNullTolerant_andCountEverySection() {
        LandingDefinition definition = new LandingDefinition(PageType.CLP, "es-mx", null, null,
                Map.of(PageSection.TOP_CONTENT, List.of(), PageSection.BLOCKS, List.of(
                        new BlockDefinition("b", "container", null, true, true, Map.of()))));
        java.util.Map<PageSection, List<LandingBlock>> withNull = new java.util.EnumMap<>(PageSection.class);
        withNull.put(PageSection.BLOCKS, null);
        LandingPage page = new LandingPage(PageType.CLP, "es-mx", null, withNull, null);

        assertThat(definition.blockCount()).isEqualTo(1);
        assertThat(definition.entryMetadata()).isEmpty();
        assertThat(page.blocks()).isEmpty();
        assertThat(page.blocks(PageSection.BOTTOM_CONTENT)).isEmpty();
        assertThat(page.entryMetadata()).isEmpty();
        assertThat(new LandingPage(PageType.BLP, "es-mx", null, null, null).sections()).isEmpty();
        assertThat(new LandingDefinition(PageType.BLP, "es-mx", null, null, null).sections()).isEmpty();
    }

    @Test
    void categoryData_carriesARedirectOnlyWhenGivenOne() {
        CategoryData category = new CategoryData("C1", "N", "N", "/u", false, null, null, null, "home", null);

        assertThat(category.ancestry()).isEmpty();
        assertThat(category.children()).isEmpty();
        assertThat(category.redirectContent()).isNull();
        assertThat(category.withRedirect(Map.of("hasRedirect", true)).redirectContent())
                .containsEntry("hasRedirect", true);
    }
}
