package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
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
class ProductsListSalesforceBlockResolverTest {

    private static final String USER_ID_HEADER = "x-sf-user-id";
    private static final String SESSION_ID = "f2207b1b-a5f4-47cd-b70a-fcf4072c2d04.c2ln";

    private static final SalesforceProperties PROPS = new SalesforceProperties("Web", USER_ID_HEADER);

    /** The supported shape (ADR-041): a SessionId cookie travelled AND the header carries the id. */
    private static final SessionContext WITH_HEADER = new SessionContext(
            true, "LP", "WEB", "es-mx", Map.of(USER_ID_HEADER, "31216912118"), SESSION_ID);
    /** Session present, header absent — Salesforce has no id to personalise on. */
    private static final SessionContext NO_HEADER = new SessionContext(
            true, "LP", "WEB", "es-mx", Map.of(), SESSION_ID);
    /** Header present but no SessionId cookie — Salesforce V1 only serves logged-in shoppers. */
    private static final SessionContext NO_SESSION = new SessionContext(
            false, "LP", "WEB", "es-mx", Map.of(USER_ID_HEADER, "31216912118"), null);

    @Mock
    private ProductsListService productsListService;
    @Mock
    private ProductsListMapper productsListMapper;

    @Test
    void resolutionKeys_claimSalesforceBackedAndSourcelessProductsList() {
        assertThat(resolver().resolutionKeys())
                .containsExactlyInAnyOrder(
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE));
    }

    @Test
    void resolutionKeys_doNotClaimJewelBackedProductsList() {
        assertThat(resolver().resolutionKeys())
                .doesNotContain(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL));
    }

    @Test
    void resolve_mapsHeaderProfileIdIntoQuery_andWrapsMapperResponse() {
        ProductsListResponse response = new ProductsListResponse("b1", "title", "campaign", List.of());
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any())).thenReturn(response);

        ResolvedContent content = resolver().resolve(context(Map.of()));

        assertThat(content.payload()).isSameAs(response);
        assertThat(capturedQuery().blockId()).isEqualTo("b1");
        assertThat(capturedQuery().source()).isEqualTo(SourceOfData.SALESFORCE);
        // ADR-041: the id comes from x-sf-user-id, not from session.authenticatedProfileId() ("u9").
        assertThat(capturedSettings().userId()).isEqualTo("31216912118");
    }

    /**
     * ADR-041's first condition: without a {@code SessionId} cookie the header alone is not an
     * identity, because Salesforce V1 only resolves logged-in shoppers.
     */
    @Test
    void resolve_headerWithoutSessionId_passesNullUserId() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", "t", "campaign", List.of()));

        resolver().resolve(contextFor(NO_SESSION));

        assertThat(capturedSettings().userId()).isNull();
    }

    /** ADR-041's second condition: the header *is* the profile id, so its absence resolves nothing. */
    @Test
    void resolve_sessionWithoutHeader_passesNullUserId() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", "t", "campaign", List.of()));

        resolver().resolve(contextFor(NO_HEADER));

        assertThat(capturedSettings().userId()).isNull();
    }

    /**
     * Each carousel on the live page runs a different Salesforce action; resolving them all against the
     * configured default returns the wrong campaign.
     */
    @Test
    void resolve_passesBlockCarouselAndThresholdsIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", "t", "campaign", List.of()));

        resolver().resolve(context(Map.of(
                "salesforce_carousel", "CMSTePuedeGustar",
                "min_of_products", "6",
                "max_of_products", "20")));

        SalesforceSettings settings = capturedSettings();
        assertThat(settings.carousel()).isEqualTo("CMSTePuedeGustar");
        assertThat(settings.minOfProducts()).isEqualTo(6);
        assertThat(settings.maxOfProducts()).isEqualTo(20);
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

    private ProductsListSalesforceBlockResolver resolver() {
        return new ProductsListSalesforceBlockResolver(productsListService, productsListMapper, PROPS);
    }

    private ProductsListQuery capturedQuery() {
        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        verify(productsListService).resolve(captor.capture());
        return captor.getValue();
    }

    private SalesforceSettings capturedSettings() {
        return (SalesforceSettings) capturedQuery().provider();
    }

    private static ProductsListResolution resolutionWithOneProduct() {
        return ProductsListResolution.available(
                "b1", "title", "campaign", List.of(product("SKU-1", "One")));
    }

    private static BlockResolutionContext context(Map<String, Object> attributes) {
        return new BlockResolutionContext(
                "b1", BlockType.PRODUCTS_LIST, attributes, WITH_HEADER, Map.of(), "/p", "POST");
    }

    private static BlockResolutionContext contextFor(SessionContext session) {
        return new BlockResolutionContext(
                "b1", BlockType.PRODUCTS_LIST, Map.of(), session, Map.of(), "/p", "POST");
    }

    /** Minimal product carrying the published contract; only asserted fields are set. */
    private static ProductRecommendation product(String productId, String name) {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId(productId);
        p.setName(name);
        return p;
    }
}
