package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
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
class ProductsListSalesforceV2BlockResolverTest {

    private static final SalesforceV2Properties PROPS =
            new SalesforceV2Properties("x-sf-user-id", "x-sf-device-id");

    /** No SessionId cookie on purpose: V2 identity is header passthrough, unlike V1's ADR-041 gate. */
    private static final SessionContext SESSION = new SessionContext(
            false, "LP", "WEB", "es-mx",
            Map.of("x-sf-user-id", "user-1", "x-sf-device-id", "dev-1"), null);

    private static final Map<String, Object> V2_BLOCK = Map.of(
            "source_of_data", "salesforce_v2",
            "salesforce_v2_config", Map.of("model", "home-top-sellers"));

    @Mock
    private ProductsListService productsListService;
    @Mock
    private ProductsListMapper productsListMapper;

    @Test
    void resolutionKeys_claimOnlySalesforceV2() {
        assertThat(resolver().resolutionKeys())
                .containsExactly(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2));
    }

    @Test
    void resolve_buildsV2SettingsFromTheBlockAndTheHeaders_andWrapsMapperResponse() {
        ProductsListResponse response = new ProductsListResponse("b1", null, null, List.of());
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any())).thenReturn(response);

        ResolvedContent content = resolver().resolve(context(V2_BLOCK, SESSION));

        assertThat(content.payload()).isSameAs(response);
        SalesforceV2Settings settings = (SalesforceV2Settings) capturedQuery().provider();
        assertThat(settings.model()).isEqualTo("home-top-sellers");
        assertThat(settings.userId()).isEqualTo("user-1");
        assertThat(settings.deviceId()).isEqualTo("dev-1");
        assertThat(capturedQuery().blockId()).isEqualTo("b1");
    }

    @Test
    void resolve_noSession_passesNoIdentity() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(V2_BLOCK, null));

        assertThat(((SalesforceV2Settings) capturedQuery().provider()).individualId()).isNull();
    }

    @Test
    void resolve_emptyProducts_dropsBlock() {
        when(productsListService.resolve(any()))
                .thenReturn(ProductsListResolution.empty("b1", BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS));

        assertThat(resolver().resolve(context(V2_BLOCK, SESSION))).isNull();
    }

    /**
     * The eager path hands the block's A/B rule to the use case, which applies it exactly as the lazy
     * endpoint does and as commons' shared evaluator does — the resolver never decides the cohort.
     */
    @Test
    void resolve_passesTheBlockConditionalShowHideIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));
        Map<String, Object> block = new java.util.LinkedHashMap<>(V2_BLOCK);
        block.put("conditional_show_hide",
                Map.of("active", true, "action", "show", "header_name", "x-ab-carousel"));

        resolver().resolve(context(block, SESSION));

        assertThat(capturedQuery().conditionalShowHide())
                .isEqualTo(new ConditionalShowHide(true, "show", "x-ab-carousel", null));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private ProductsListSalesforceV2BlockResolver resolver() {
        return new ProductsListSalesforceV2BlockResolver(productsListService, productsListMapper, PROPS);
    }

    private ProductsListQuery capturedQuery() {
        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        verify(productsListService).resolve(captor.capture());
        return captor.getValue();
    }

    private static ProductsListResolution resolutionWithOneProduct() {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId("SKU-1");
        return ProductsListResolution.available("b1", null, "home-top-sellers", List.of(p));
    }

    private static BlockResolutionContext context(Map<String, Object> attributes, SessionContext session) {
        return new BlockResolutionContext(
                "b1", BlockType.PRODUCTS_LIST, attributes, session, Map.of(), "/p", "POST");
    }
}
