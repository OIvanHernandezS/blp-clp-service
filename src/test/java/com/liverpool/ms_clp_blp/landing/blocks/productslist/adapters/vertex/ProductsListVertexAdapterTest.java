package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.liverpool.dto.PlpRequestPayloadDTO;
import com.liverpool.ms_clp_blp.clients.vertex.VertexClient;
import com.liverpool.ms_clp_blp.clients.vertex.VertexProperties;
import com.liverpool.ms_clp_blp.clients.vertex.dto.VertexSearchResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
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
 * Unit tests for {@link ProductsListVertexAdapter}: verifies the missing-category guard, request
 * construction, response mapping and error translation.
 *
 * <p>The {@link VertexClient} Feign interface is mocked, so these stay fast unit tests. Stubbed
 * responses are deserialized from the same JSON the upstream returns, which keeps the wire shape —
 * including the {@code @JsonIgnoreProperties} tolerance — under test. The real HTTP contract (URL,
 * Basic auth, Jackson wiring) is covered separately by {@code VertexClientWireTest}.</p>
 */
class ProductsListVertexAdapterTest {

    private static final String SEARCH_PATH = "/v2/search";
    private static final String BLOCK_ID = "bltvertex123";

    private static final VertexProperties PROPS =
            new VertexProperties("http://plp-search", SEARCH_PATH, "user", "pass", Duration.ofSeconds(4), 12);

    private static final SessionContext SESSION = new SessionContext(true, "LP", "WEB", "es-mx");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VertexClient vertexClient;
    private ProductsListVertexAdapter adapter;

    @BeforeEach
    void setUp() {
        vertexClient = mock(VertexClient.class);
        adapter = new ProductsListVertexAdapter(vertexClient, PROPS);
    }

    @Test
    void source_isVertex() {
        assertThat(adapter.source()).isEqualTo(SourceOfData.VERTEX);
    }

    // ── missing category is a business no-op, not a failure ─────────────────────────────────────

    @Test
    void skipReason_nullCategoryId_isMissingCategory() {
        assertThat(adapter.skipReason(query(null, null, null)))
                .isEqualTo(BlockDropReason.MISSING_CATEGORY);
        verifyNoInteractions(vertexClient); // no call, no breaker permit consumed
    }

    @Test
    void skipReason_blankCategoryId_isMissingCategory() {
        assertThat(adapter.skipReason(query("  ", null, null)))
                .isEqualTo(BlockDropReason.MISSING_CATEGORY);
    }

    @Test
    void skipReason_presentCategoryId_isNull() {
        assertThat(adapter.skipReason(query("cat-1", null, null))).isNull();
    }

    /** A category search carries no personalisation, unlike Salesforce. */
    @Test
    void sessionIndependent_isAlwaysTrue() {
        assertThat(adapter.sessionIndependent(query("cat-1", null, null))).isTrue();
        assertThat(adapter.sessionIndependent(query(null, null, null))).isTrue();
    }

    // ── request body ──────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_requestBody_containsCategoryPageSizeAndBrand() {
        stub("{}");

        adapter.fetch(query("cat-1", null, 20));

        PlpRequestPayloadDTO sent = captureRequest();
        assertThat(sent.getCategoryId()).isEqualTo("cat-1");
        assertThat(sent.getPageSize()).isEqualTo(20);
        assertThat(sent.getBrand()).isEqualTo("LP");
        assertThat(sent.getChannel()).isEqualTo("WEB");
    }

    @Test
    void fetch_noMaxOfProducts_usesConfiguredDefaultPageSize() {
        stub("{}");

        adapter.fetch(query("cat-1", null, null));

        assertThat(captureRequest().getPageSize()).isEqualTo(12);
    }

    @Test
    void fetch_zeroOrNegativeMaxOfProducts_fallsBackToDefaultPageSize() {
        stub("{}");

        adapter.fetch(query("cat-1", null, 0));

        assertThat(captureRequest().getPageSize()).isEqualTo(12);
    }

    // ── response mapping ──────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_mapsRecordsFromResponse() {
        stub("""
                {
                  "meta": { "status": "SUCCESS", "statusCode": 200 },
                  "data": {
                    "records": [
                      { "productId": "sku-1", "title": "Product One" },
                      { "productId": "sku-2", "title": "Product Two" }
                    ]
                  }
                }
                """);

        ProductsListResolution result = adapter.fetch(query("cat-1", null, null));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.products()).hasSize(2);
        assertThat(result.products().get(0).getProductId()).isEqualTo("sku-1");
        assertThat(result.products().get(1).getProductId()).isEqualTo("sku-2");
    }

    @Test
    void fetch_unknownTopLevelFields_areIgnored() {
        // ApiResponseDTO/PlpResponseDto carry many more fields than this adapter needs; the local
        // projection must tolerate them (@JsonIgnoreProperties).
        stub("""
                {
                  "meta": { "status": "SUCCESS" },
                  "data": {
                    "pageType": "CLP",
                    "nullSearch": false,
                    "records": [ { "productId": "sku-1", "title": "One" } ],
                    "source": "VertexAI"
                  }
                }
                """);

        assertThat(adapter.fetch(query("cat-1", null, null)).products()).hasSize(1);
    }

    @Test
    void fetch_emptyRecords_returnsEmptyResolution() {
        stub("""
                { "data": { "records": [] } }
                """);

        ProductsListResolution result = adapter.fetch(query("cat-1", null, null));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.products()).isEmpty();
    }

    @Test
    void fetch_noDataKey_returnsEmptyResolution() {
        stub("{}");

        assertThat(adapter.fetch(query("cat-1", null, null)).products()).isEmpty();
    }

    // ── error handling ────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_vertexReturns500_throwsDynamicBlockServiceUnavailableException() {
        when(vertexClient.search(any())).thenThrow(serverError());

        assertThatThrownBy(() -> adapter.fetch(query("cat-1", null, null)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getBlockType()).isEqualTo("products_list");
                });
    }

    /**
     * Connect/read timeouts surface as {@link feign.RetryableException}, a {@code FeignException}
     * subclass — the adapter's single catch must cover transport failures as well as error statuses.
     */
    @Test
    void fetch_transportFailure_throwsDynamicBlockServiceUnavailableException() {
        when(vertexClient.search(any()))
                .thenThrow(new feign.RetryableException(
                        -1, "connect timed out", Request.HttpMethod.POST, (Long) null, dummyRequest()));

        assertThatThrownBy(() -> adapter.fetch(query("cat-1", null, null)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void stub(String json) {
        when(vertexClient.search(any())).thenReturn(MAPPER.readValue(json, VertexSearchResponse.class));
    }

    private PlpRequestPayloadDTO captureRequest() {
        ArgumentCaptor<PlpRequestPayloadDTO> captor = ArgumentCaptor.forClass(PlpRequestPayloadDTO.class);
        verify(vertexClient).search(captor.capture());
        return captor.getValue();
    }

    private static FeignException serverError() {
        return new FeignException.InternalServerError("boom", dummyRequest(), null, Map.of());
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "http://plp-search" + SEARCH_PATH,
                Map.of(), null, StandardCharsets.UTF_8, null);
    }

    private static ProductsListQuery query(String categoryId, Integer minOfProducts, Integer maxOfProducts) {
        return new ProductsListQuery(
                BLOCK_ID, SESSION, new VertexSettings(categoryId, minOfProducts, maxOfProducts),
                ConditionalShowHide.NONE);
    }
}
