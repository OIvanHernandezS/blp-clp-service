package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductsListJewelBlockResolverTest {

    private static final SessionContext AUTH = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final JewelProperties PROPERTIES =
            new JewelProperties("x-jml-user-id", "x-jml-device-id", 3, 15, true);

    @Mock
    private ProductsListService productsListService;
    @Mock
    private ProductsListMapper productsListMapper;

    /**
     * ADR-029: the only shape the CMS ships for Jewel is {@code products_list} + {@code source_of_data:
     * jewel}. The former second key, {@code jewel_product_recommendation}, was never a content type —
     * it is a digital_bff filename — so claiming it was dead routing.
     */
    @Test
    void resolutionKeys_claimOnlyJewelBackedProductsList() {
        assertThat(resolver().resolutionKeys())
                .containsExactly(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL));
    }

    @Test
    void resolutionKeys_doNotClaimSalesforceBackedOrSourcelessProductsList() {
        assertThat(resolver().resolutionKeys())
                .doesNotContain(
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE));
    }

    @Test
    void resolve_readsConfiguredJewelHeaders_andWrapsMapperResponse() {
        ProductsListResponse response = new ProductsListResponse("b1", null, null, List.of());
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any())).thenReturn(response);

        ResolvedContent content = resolver().resolve(context(Map.of()));

        assertThat(content.payload()).isSameAs(response);
        JewelSettings settings = capturedSettings();
        assertThat(settings.userId()).isEqualTo("ju9");
        assertThat(settings.deviceId()).isEqualTo("dev7");
    }

    @Test
    void resolve_passesBlockIdIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of()));

        assertThat(capturedQuery().blockId()).isEqualTo("b1");
        assertThat(capturedQuery().source()).isEqualTo(SourceOfData.JEWEL);
    }

    /**
     * The block's Contentstack settings must reach the adapter — resolving every carousel against the
     * single configured default model is what produced zero products on a live landing page.
     */
    @Test
    void resolve_passesBlockModelConfigIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of(
                "jewel_model_config", Map.of(
                        "jewel_model", "global-tps-sales",
                        "jewel_requires_device_id", true,
                        "jewel_requires_user_id", false),
                "min_of_products", "6",
                "max_of_products", "20")));

        JewelModelConfig config = capturedSettings().modelConfig();
        assertThat(config.model()).isEqualTo("global-tps-sales");
        assertThat(config.requiresDeviceId()).isTrue();
        assertThat(config.requiresUserId()).isFalse();
        assertThat(config.minItems()).isEqualTo(6);
        assertThat(config.maxItems()).isEqualTo(20);
    }

    @Test
    void resolve_blockWithoutModelConfig_passesNullSoAdapterFallsBackToDefaults() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of()));

        assertThat(capturedSettings().modelConfig()).isNull();
    }

    /** Legacy parity: an empty carousel is removed from the page rather than rendered empty. */
    @Test
    void resolve_emptyProducts_dropsBlock() {
        when(productsListService.resolve(any())).thenReturn(ProductsListResolution.empty("b1", BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS));

        assertThat(resolver().resolve(context(Map.of()))).isNull();
    }

    /**
     * The eager path hands the block's A/B rule to the use case, which applies it exactly as the lazy
     * endpoint does — the resolver never decides the cohort itself.
     */
    @Test
    void resolve_passesTheBlockConditionalShowHideIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of("conditional_show_hide",
                Map.of("active", true, "action", "hide", "cookie_name", "ab_carousel"))));

        assertThat(capturedQuery().conditionalShowHide())
                .isEqualTo(new ConditionalShowHide(true, "hide", null, "ab_carousel"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private ProductsListJewelBlockResolver resolver() {
        return new ProductsListJewelBlockResolver(productsListService, productsListMapper, PROPERTIES);
    }

    private ProductsListQuery capturedQuery() {
        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        verify(productsListService).resolve(captor.capture());
        return captor.getValue();
    }

    private JewelSettings capturedSettings() {
        return (JewelSettings) capturedQuery().provider();
    }

    private static ProductsListResolution resolutionWithOneProduct() {
        return ProductsListResolution.available(
                "b1", null, null, List.of(product("SKU-1", "Product")));
    }

    private static BlockResolutionContext context(Map<String, Object> attributes) {
        return new BlockResolutionContext(
                "b1", BlockType.PRODUCTS_LIST, attributes, AUTH,
                Map.of("x-jml-user-id", "ju9", "x-jml-device-id", "dev7"), "/p", "POST");
    }

    /** Minimal product carrying the published contract; only the fields these tests assert are set. */
    private static ProductRecommendation product(String productId, String name) {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId(productId);
        p.setName(name);
        return p;
    }
}
