package com.liverpool.ms_clp_blp.landing;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.PageType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LandingCompositionsTest {

    private static final LandingCompositionService SERVICE =
            new LandingCompositionService((flag, brand) -> true, new BlockResolutionCatalog(Map.of()));

    @Test
    void everyPageTypeNeedsAComposition_atStartup() {
        assertThatThrownBy(() -> new LandingCompositions(Map.of(PageType.BLP, SERVICE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLP");
    }

    @Test
    void routesByPageType() {
        LandingCompositionService clp =
                new LandingCompositionService((flag, brand) -> true, new BlockResolutionCatalog(Map.of()));
        LandingCompositions compositions = new LandingCompositions(Map.of(PageType.BLP, SERVICE, PageType.CLP, clp));

        assertThat(compositions.forPage(PageType.BLP)).isSameAs(SERVICE);
        assertThat(compositions.forPage(PageType.CLP)).isSameAs(clp);
    }

    @Test
    void theProductsListEndpoint_isPerPageType_andOverridable() {
        LandingProperties defaults = new LandingProperties(Map.of(), Map.of(), null);
        LandingProperties overridden = new LandingProperties(Map.of(),
                Map.of("clp-products-list-endpoint", "/v2/clp/blocks/products-list"), null);

        assertThat(LandingConfig.productsListEndpoint(defaults, PageType.BLP)).isEqualTo("/blp/blocks/products-list");
        assertThat(LandingConfig.productsListEndpoint(overridden, PageType.CLP)).isEqualTo("/v2/clp/blocks/products-list");
    }
}
