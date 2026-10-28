package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
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
class ProductsListVertexBlockResolverTest {

    private static final SessionContext SESSION = new SessionContext(true, "LP", "WEB", "es-mx");

    @Mock
    private ProductsListService productsListService;
    @Mock
    private ProductsListMapper productsListMapper;

    @Test
    void resolutionKeys_claimVertexAndItsGroupbyAlias() {
        assertThat(resolver().resolutionKeys())
                .containsExactlyInAnyOrder(
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY));
    }

    @Test
    void resolutionKeys_doNotClaimOtherProvidersOrSourceless() {
        assertThat(resolver().resolutionKeys())
                .doesNotContain(
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE));
    }

    @Test
    void resolve_readsNestedProductsDataCategory_andWrapsMapperResponse() {
        ProductsListResponse response = new ProductsListResponse("b1", null, null, List.of());
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any())).thenReturn(response);

        ResolvedContent content = resolver().resolve(context(Map.of(
                "products_data", Map.of("category", "cat-1"))));

        assertThat(content.payload()).isSameAs(response);
        assertThat(capturedSettings().categoryId()).isEqualTo("cat-1");
    }

    @Test
    void resolve_passesBlockIdAndSourceIntoQuery() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of("products_data", Map.of("category", "cat-1"))));

        assertThat(capturedQuery().blockId()).isEqualTo("b1");
        assertThat(capturedQuery().source()).isEqualTo(SourceOfData.VERTEX);
    }

    @Test
    void resolve_noProductsData_passesNullCategorySoAdapterSkipsTheCall() {
        when(productsListService.resolve(any())).thenReturn(resolutionWithOneProduct());
        when(productsListMapper.toResponse(any()))
                .thenReturn(new ProductsListResponse("b1", null, null, List.of()));

        resolver().resolve(context(Map.of()));

        assertThat(capturedSettings().categoryId()).isNull();
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

    private ProductsListVertexBlockResolver resolver() {
        return new ProductsListVertexBlockResolver(productsListService, productsListMapper);
    }

    private ProductsListQuery capturedQuery() {
        ArgumentCaptor<ProductsListQuery> captor = ArgumentCaptor.forClass(ProductsListQuery.class);
        verify(productsListService).resolve(captor.capture());
        return captor.getValue();
    }

    private VertexSettings capturedSettings() {
        return (VertexSettings) capturedQuery().provider();
    }

    private static ProductsListResolution resolutionWithOneProduct() {
        return ProductsListResolution.available(
                "b1", null, null, List.of(product("SKU-1", "Product")));
    }

    private static BlockResolutionContext context(Map<String, Object> attributes) {
        return new BlockResolutionContext(
                "b1", BlockType.PRODUCTS_LIST, attributes, SESSION, Map.of(), "/p", "POST");
    }

    /** Minimal product carrying the published contract; only the fields these tests assert are set. */
    private static ProductRecommendation product(String productId, String name) {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId(productId);
        p.setName(name);
        return p;
    }
}
