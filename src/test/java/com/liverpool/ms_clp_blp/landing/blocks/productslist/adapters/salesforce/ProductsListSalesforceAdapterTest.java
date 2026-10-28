package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.SalesforceRequest;
import com.liverpool.dto.salesforce.SalesforceResponse;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceCarrouselsClient;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import feign.FeignException;
import feign.Request;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductsListSalesforceAdapter}: the drop ladder, the request envelope
 * (including category scoping), all three response shapes, campaign selection, and HTTP error
 * translation.
 *
 * <p>No live Salesforce connection required — HTTP goes through a mocked
 * {@link SalesforceCarrouselsClient}. Stub bodies are deserialized into commons'
 * {@link SalesforceResponse} with ms-clp-blp's own (Jackson 3) mapper, so these tests also prove that the
 * commons DTOs' Jackson 2 annotations still bind here.</p>
 */
class ProductsListSalesforceAdapterTest {

    private static final String BASE_URL = "http://salesforce";
    private static final String ACTIONS_PATH = "/api2/authevent/liverpool";

    private static final SalesforceProperties PROPS = new SalesforceProperties("Web", "x-sf-user-id");

    private static final SessionContext AUTH_SESSION = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final String USER_ID = "user-31005309570";
    private static final String BLOCK_ID = "bltabc123";
    private static final String ACTION = "CMSTePuedeGustar";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SalesforceCarrouselsClient salesforceClient;
    private ProductsListSalesforceAdapter adapter;

    @BeforeEach
    void setUp() {
        salesforceClient = mock(SalesforceCarrouselsClient.class);
        adapter = new ProductsListSalesforceAdapter(salesforceClient, PROPS);
    }

    /** Stubs the Evergage decision response from the JSON the live service returns. */
    private void stub(String json) {
        when(salesforceClient.post(any()))
                .thenReturn(ResponseEntity.ok(MAPPER.readValue(json, SalesforceResponse.class)));
    }

    private void stubServerError() {
        when(salesforceClient.post(any())).thenThrow(new FeignException.InternalServerError(
                "boom",
                Request.create(Request.HttpMethod.POST, BASE_URL + ACTIONS_PATH,
                        Map.of(), null, StandardCharsets.UTF_8, null),
                null, Map.of()));
    }

    /**
     * Asserts on the envelope actually handed to the client. Both sides are normalised through Jackson
     * so the comparison is structural rather than order- or type-sensitive.
     */
    @SuppressWarnings("unchecked")
    private void assertRequestBody(String expectedJson) {
        ArgumentCaptor<SalesforceRequest> captor = ArgumentCaptor.forClass(SalesforceRequest.class);
        verify(salesforceClient).post(captor.capture());
        Map<String, Object> actual =
                MAPPER.readValue(MAPPER.writeValueAsString(captor.getValue()), Map.class);
        assertThat(actual).containsAllEntriesOf(MAPPER.readValue(expectedJson, Map.class));
    }

    private static ProductsListQuery query(String userId) {
        return query(userId, ACTION, null);
    }

    private static ProductsListQuery query(String userId, String action, String categoryId) {
        return new ProductsListQuery(BLOCK_ID, AUTH_SESSION,
                new SalesforceSettings(userId, action, categoryId, null, null), ConditionalShowHide.NONE);
    }

    // ── drop ladder: every gate is pure, so nothing reaches the wire ─────────────────────────────

    /**
     * ADR-029 moved this guard from {@code fetch} to {@code skipReason}, so a guest never consumes a
     * circuit-breaker permit — a session Salesforce cannot personalise is a business case, not a call.
     * ms-clp-blp deliberately does not adopt commons' device-id fallback (ADR-033).
     *
     * <p>The reason is asserted, not just the rejection: it is what the endpoint returns to the
     * frontend as {@code reason} beside an {@code EMPTY} status (ADR-043).</p>
     */
    @Test
    void skipReason_nullUserId_isMissingUserIdentity() {
        assertThat(adapter.skipReason(query(null))).isEqualTo(BlockDropReason.MISSING_USER_IDENTITY);
        verifyNoInteractions(salesforceClient); // no call, no breaker permit consumed
    }

    @Test
    void skipReason_blankUserId_isMissingUserIdentity() {
        assertThat(adapter.skipReason(query("  "))).isEqualTo(BlockDropReason.MISSING_USER_IDENTITY);
    }

    /**
     * A block with no action configured is dropped rather than served the service-wide default: each
     * carousel runs its own campaign, so a default would render the wrong one (ADR-022).
     */
    @Test
    void skipReason_blankCarouselAction_isMissingCampaign() {
        assertThat(adapter.skipReason(query(USER_ID, null, null)))
                .isEqualTo(BlockDropReason.MISSING_CAMPAIGN);
        assertThat(adapter.skipReason(query(USER_ID, "   ", null)))
                .isEqualTo(BlockDropReason.MISSING_CAMPAIGN);
        verifyNoInteractions(salesforceClient);
    }

    /** SimilarItems_* recommends against an anchor product, which the Home page never has. */
    @Test
    void skipReason_similarItemsAction_isMissingAnchorProduct() {
        assertThat(adapter.skipReason(query(USER_ID, "SimilarItems_1", null)))
                .isEqualTo(BlockDropReason.MISSING_ANCHOR_PRODUCT);
        assertThat(adapter.skipReason(query(USER_ID, "similaritems_2", null)))
                .isEqualTo(BlockDropReason.MISSING_ANCHOR_PRODUCT);
        verifyNoInteractions(salesforceClient);
    }

    /** {@code null} is the port's "nothing stands in the way" answer. */
    @Test
    void skipReason_authenticatedUserIdAndAction_isNull() {
        assertThat(adapter.skipReason(query(USER_ID))).isNull();
    }

    /** Every Salesforce carousel personalises on the ATG profile id, so none is edge-cacheable. */
    @Test
    void sessionIndependent_isAlwaysFalse() {
        assertThat(adapter.sessionIndependent(query(USER_ID))).isFalse();
        assertThat(adapter.sessionIndependent(query(null))).isFalse();
    }

    @Test
    void source_isSalesforce() {
        assertThat(adapter.source()).isEqualTo(SourceOfData.SALESFORCE);
    }

    // ── request body structure ────────────────────────────────────────────────────────────────────

    @Test
    void fetch_requestBody_containsRequiredFields() {
        stub("{}");

        adapter.fetch(query(USER_ID));

        assertRequestBody("""
                {
                  "action":  "CMSTePuedeGustar",
                  "flags":   { "noCampaigns": false },
                  "source":  { "channel": "Server", "application": "Web" },
                  "user":    { "attributes": { "ID_ATG1": "user-31005309570" } }
                }
                """);
    }

    @Test
    void fetch_usesTheBlocksOwnCarouselAction() {
        stub("{}");

        adapter.fetch(query(USER_ID, "CMSOfertasIncreibles", null));

        assertRequestBody("""
                { "action": "CMSOfertasIncreibles" }
                """);
    }

    /** salesforce_category_id is what scopes the campaign to a category; omitting it returns another. */
    @Test
    void fetch_categoryId_isSentAsSourceUrl() {
        stub("{}");

        adapter.fetch(query(USER_ID, ACTION, "cat-4020"));

        assertRequestBody("""
                { "source": { "channel": "Server", "application": "Web", "url": "cat-4020" } }
                """);
    }

    @Test
    void fetch_blankCategoryId_omitsSourceUrlEntirely() {
        stub("{}");

        adapter.fetch(query(USER_ID, ACTION, "   "));

        ArgumentCaptor<SalesforceRequest> captor = ArgumentCaptor.forClass(SalesforceRequest.class);
        verify(salesforceClient).post(captor.capture());
        assertThat(captor.getValue().getSource().getUrl()).isNull();
    }

    // ── productDataMapped response path ──────────────────────────────────────────────────────────

    @Test
    void fetch_productDataMapped_mapsSkuTitleAndPrice() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "LandingCarousel",
                    "payload": {
                      "title": "Best Sellers",
                      "productDataMapped": [{
                        "productId": "sku-001",
                        "title": "Product A",
                        "priceInfo": { "listPrice": { "price": "999.00" } }
                      }]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.title()).isEqualTo("Best Sellers");
        assertThat(result.campaignName()).isEqualTo("LandingCarousel");
        assertThat(result.products()).hasSize(1);

        ProductRecommendation product = result.products().get(0);
        assertThat(product.getProductId()).isEqualTo("sku-001");
        assertThat(product.getName()).isEqualTo("Product A");
        assertThat(product.getPriceInfo().getPrice()).isEqualTo(999.00d);
    }

    @Test
    void fetch_productDataMapped_multipleProducts_allMapped() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "MultiCarousel",
                    "payload": {
                      "title": "Top Picks",
                      "productDataMapped": [
                        { "productId": "sku-001", "title": "A", "priceInfo": { "listPrice": { "price": "10.00" } } },
                        { "productId": "sku-002", "title": "B", "priceInfo": { "listPrice": { "price": "20.00" } } },
                        { "productId": "sku-003", "title": "C", "priceInfo": null }
                      ]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.products()).hasSize(3);
        assertThat(result.products().get(0).getProductId()).isEqualTo("sku-001");
        assertThat(result.products().get(1).getProductId()).isEqualTo("sku-002");
        // null priceInfo → absent price (no NPE), so the frontend can tell "no price" from "free"
        assertThat(result.products().get(2).getPriceInfo().getPrice()).isNull();
    }

    // ── productToShow: the shape that used to be unread ──────────────────────────────────────────

    /**
     * The regression this change exists for: a campaign serving {@code productToShow} previously mapped
     * to zero products, which dropped the whole carousel.
     */
    @Test
    void fetch_productToShow_isMapped() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "ToShowCarousel",
                    "payload": {
                      "title": "Para ti",
                      "productToShow": [{
                        "id": "sku-show-001",
                        "categories": ["cat-1", "cat-2"],
                        "dimensions": { "Brand": ["Levi's"] },
                        "attributes": {
                          "name":      { "value": "Shown Product" },
                          "price":     { "value": "1,299.00" },
                          "listPrice": { "value": "999.00" },
                          "imageUrl":  { "value": "https://img/1.jpg" },
                          "rating":    { "value": "4.5" }
                        }
                      }]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.title()).isEqualTo("Para ti");
        assertThat(result.products()).hasSize(1);

        ProductRecommendation product = result.products().get(0);
        assertThat(product.getProductId()).isEqualTo("sku-show-001");
        assertThat(product.getName()).isEqualTo("Shown Product");
        // Salesforce inverts these on the raw shape: `price` is the list price, `listPrice` the promo.
        assertThat(product.getPriceInfo().getOriginalPrice()).isEqualTo(1299.00d);
        assertThat(product.getPriceInfo().getPromoPrice()).isEqualTo(999.00d);
        assertThat(product.getBrand()).isEqualTo("Levi's");
        assertThat(product.getRating().getAverage()).isEqualTo("4.5");
        // Read from the product's own `categories`, which is where Salesforce actually puts them.
        assertThat(product.getCategories()).extracting(ProductRecommendation.Category::getId)
                .containsExactly("cat-1", "cat-2");
    }

    /** productToShow wins over products, which Salesforce duplicates the same items into. */
    @Test
    void fetch_productToShow_takesPrecedenceOverProducts() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "Both",
                    "payload": {
                      "productToShow": [{ "id": "to-show", "attributes": {} }],
                      "products":      [{ "id": "plain",   "attributes": {} }]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("to-show");
    }

    // ── raw products fallback path ────────────────────────────────────────────────────────────────

    @Test
    void fetch_productsRawFallback_whenNoOtherShapePresent() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "RawCarousel",
                    "payload": {
                      "title": "Raw Products",
                      "products": [{
                        "id": "sku-raw-001",
                        "attributes": {
                          "name": { "value": "Raw Product A" },
                          "listPrice": { "value": "49.99" }
                        }
                      }]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.title()).isEqualTo("Raw Products");
        assertThat(result.products()).hasSize(1);
        ProductRecommendation product = result.products().get(0);
        assertThat(product.getProductId()).isEqualTo("sku-raw-001");
        assertThat(product.getName()).isEqualTo("Raw Product A");
        assertThat(product.getPriceInfo().getPrice()).isEqualTo(49.99d);
    }

    @Test
    void fetch_productDataMappedTakesPrecedenceOverRawProducts() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "Both",
                    "payload": {
                      "productDataMapped": [{ "productId": "mapped-sku", "title": "Mapped", "priceInfo": null }],
                      "products":          [{ "id": "raw-sku",    "attributes": {} }]
                    }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("mapped-sku");
    }

    // ── campaign selection ───────────────────────────────────────────────────────────────────────

    /**
     * Salesforce may lead with a campaign that returned nothing. Taking index 0 unconditionally is how a
     * perfectly good carousel behind an empty leading campaign resolved as empty.
     */
    @Test
    void fetch_skipsEmptyLeadingCampaigns() {
        stub("""
                {
                  "campaignResponses": [
                    { "campaignName": "Empty",  "payload": { "title": "Empty Title", "productDataMapped": [] } },
                    { "campaignName": "Second", "payload": {
                        "title": "Second Title",
                        "productDataMapped": [{ "productId": "sku-2", "title": "P2", "priceInfo": null }] } }
                  ]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("sku-2");
        // title and campaign name come from the campaign that actually produced the products
        assertThat(result.title()).isEqualTo("Second Title");
        assertThat(result.campaignName()).isEqualTo("Second");
    }

    @Test
    void fetch_firstCampaignWithProductsWins() {
        stub("""
                {
                  "campaignResponses": [
                    { "campaignName": "First",  "payload": {
                        "title": "First Title",
                        "productDataMapped": [{ "productId": "sku-1", "title": "P1", "priceInfo": null }] } },
                    { "campaignName": "Second", "payload": {
                        "title": "Second Title",
                        "productDataMapped": [{ "productId": "sku-2", "title": "P2", "priceInfo": null }] } }
                  ]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.title()).isEqualTo("First Title");
        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("sku-1");
    }

    @Test
    void fetch_nullPayloadInLeadingCampaign_fallsThrough() {
        stub("""
                {
                  "campaignResponses": [
                    { "campaignName": "Broken", "payload": null },
                    { "campaignName": "Good",   "payload": {
                        "productDataMapped": [{ "productId": "sku-ok", "title": "OK", "priceInfo": null }] } }
                  ]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("sku-ok");
    }

    // ── empty / null edge cases ──────────────────────────────────────────────────────────────────

    @Test
    void fetch_emptyCampaignResponses_returnsEmptyResolution() {
        stub("{\"campaignResponses\": []}");

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        // ADR-029: the domain carries null rather than "" for an absent campaign title; the REST mapper
        // omits either, so the published response is unchanged (see ProductsListMapper.blankToNull).
        assertThat(result.title()).isNull();
        assertThat(result.products()).isEmpty();
    }

    @Test
    void fetch_noCampaignResponsesKey_returnsEmptyResolution() {
        stub("{}");

        assertThat(adapter.fetch(query(USER_ID)).products()).isEmpty();
    }

    @Test
    void fetch_nullResponseBody_returnsEmptyResolution() {
        when(salesforceClient.post(any())).thenReturn(ResponseEntity.ok(null));

        assertThat(adapter.fetch(query(USER_ID)).products()).isEmpty();
    }

    @Test
    void fetch_emptyProductArrays_returnsEmptyProductList() {
        stub("""
                {
                  "campaignResponses": [{
                    "campaignName": "EmptyCarousel",
                    "payload": { "title": "Nothing Here", "productDataMapped": [], "products": [] }
                  }]
                }
                """);

        ProductsListResolution result = adapter.fetch(query(USER_ID));

        // No campaign produced products, so there is no carousel to title.
        assertThat(result.products()).isEmpty();
        assertThat(result.title()).isNull();
    }

    // ── HTTP error handling ───────────────────────────────────────────────────────────────────────

    @Test
    void fetch_salesforceReturns500_throwsDynamicBlockServiceUnavailableException() {
        stubServerError();

        assertThatThrownBy(() -> adapter.fetch(query(USER_ID)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getBlockType()).isEqualTo("products_list");
                });
    }
}
