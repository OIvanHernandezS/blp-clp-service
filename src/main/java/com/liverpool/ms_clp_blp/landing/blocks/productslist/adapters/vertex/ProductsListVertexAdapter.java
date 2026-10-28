package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.dto.PlpRequestPayloadDTO;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.plp.RecordsResponse;
import com.liverpool.ms_clp_blp.clients.vertex.VertexClient;
import com.liverpool.ms_clp_blp.clients.vertex.VertexProperties;
import com.liverpool.ms_clp_blp.clients.vertex.dto.VertexSearchResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import feign.FeignException;

/**
 * Outbound adapter resolving {@code products_list} blocks whose {@code source_of_data} is
 * {@code vertex}, against {@code ms_plp_search}'s Vertex AI category search (Rule 19 reference:
 * {@code product_list-vertex.strategy.ts} in digital_bff — note the legacy provider called the
 * now-deprecated SearchFacade; this adapter targets {@code ms_plp_search}'s {@code POST /v2/search}
 * instead, which is {@code ms_plp_search}'s Vertex AI-backed pipeline, per ADR-021).
 *
 * <p>Calls {@code POST {baseUrl}{searchPath}} with the block's category id and page size. The circuit
 * breaker is applied at the use-case layer — this adapter intentionally has no retry logic (ADR-004).</p>
 *
 * <h3>Missing category:</h3>
 * <p>A block authored with no {@code products_data.category} is reported through {@link #skipReason},
 * so it resolves to nothing without a call and without consuming a breaker permit — consistent with the
 * BFF's {@code ContentCategoryNotInBlockError} guard, translated here into the same
 * "business no-op, not a failure" pattern Jewel/Salesforce use (ADR-022, ADR-029).</p>
 */
@Component
public class ProductsListVertexAdapter implements ProductsListPort {

    private static final Logger log = LoggerFactory.getLogger(ProductsListVertexAdapter.class);

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = "vertex";

    private final VertexClient vertexClient;
    private final VertexProperties properties;
    private final VertexProductMapper productMapper = new VertexProductMapper();

    /**
     * @param vertexClient declarative client bound to {@code ms_plp_search}, with Basic auth applied
     * @param properties   default page-size settings
     */
    public ProductsListVertexAdapter(VertexClient vertexClient, VertexProperties properties) {
        this.vertexClient = vertexClient;
        this.properties = properties;
    }

    @Override
    public SourceOfData source() {
        return SourceOfData.VERTEX;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolvable only when the block authored a category id — a Vertex carousel with no category has
     * nothing to search for, mirroring legacy's {@code ContentCategoryNotInBlockError}.</p>
     */
    @Override
    public BlockDropReason skipReason(ProductsListQuery query) {
        VertexSettings settings = settingsOf(query);
        if (isBlank(settings.categoryId())) {
            LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                    .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, SourceOfData.VERTEX)
                    .addKeyValue(LogFields.REASON, BlockDropReason.MISSING_CATEGORY)
                    .log("Block authors no Vertex category; nothing to search for");
            return BlockDropReason.MISSING_CATEGORY;
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code true}: a category search carries no per-session personalisation, unlike
     * Salesforce.</p>
     */
    @Override
    public boolean sessionIndependent(ProductsListQuery query) {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @throws DynamicBlockServiceUnavailableException when the Vertex call fails
     */
    @Override
    public ProductsListResolution fetch(ProductsListQuery query) {
        VertexSettings settings = settingsOf(query);

        PlpRequestPayloadDTO request = new PlpRequestPayloadDTO();
        request.setCategoryId(settings.categoryId());
        request.setPageSize(settings.maxOfProducts() != null && settings.maxOfProducts() > 0
                ? settings.maxOfProducts() : properties.defaultPageSize());
        request.setBrand(query.session().brand());
        request.setChannel(query.session().channel());

        VertexSearchResponse response;
        try {
            response = vertexClient.search(request);
        } catch (FeignException e) {
            // Covers both HTTP error statuses and transport failures: feign.RetryableException, which
            // wraps connect/read timeouts, is itself a FeignException. e.status() is 0 for the latter,
            // which is what distinguishes "Vertex said no" from "Vertex never answered".
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    UPSTREAM,
                    e.status(),
                    "Vertex call failed for categoryId=" + settings.categoryId() + ": " + e.getMessage(),
                    e);
        }

        return mapResponse(query.blockId(), settings.categoryId(), response);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when routed a query for another provider; defensive only, since
     *                                 the use case routes by {@link #source()}
     */
    private VertexSettings settingsOf(ProductsListQuery query) {
        if (query.provider() instanceof VertexSettings settings) {
            return settings;
        }
        throw new IllegalArgumentException(
                "ProductsListVertexAdapter cannot serve source_of_data=" + query.source());
    }

    private ProductsListResolution mapResponse(String blockId, String categoryId,
                                               VertexSearchResponse response) {
        List<RecordsResponse> records = response == null || response.data() == null
                ? List.of() : response.data().records();
        if (records == null || records.isEmpty()) {
            LogEvents.at(log, LogEvents.UPSTREAM_NO_PRODUCTS)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BLOCK_ID, blockId)
                    .addKeyValue(LogFields.SOURCE, SourceOfData.VERTEX)
                    .addKeyValue(LogFields.CATEGORY_PATH, categoryId)
                    .addKeyValue(LogFields.REASON, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS)
                    .log("Vertex answered successfully but returned no records for this category");
            return ProductsListResolution.empty(blockId, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
        }

        List<ProductRecommendation> products = productMapper.toProducts(records);

        LogEvents.at(log, LogEvents.BLOCK_RESOLVED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, blockId)
                .addKeyValue(LogFields.SOURCE, SourceOfData.VERTEX)
                .addKeyValue(LogFields.CATEGORY_PATH, categoryId)
                .addKeyValue(LogFields.PRODUCT_COUNT, products.size())
                .log("Vertex resolved the block");
        return ProductsListResolution.available(blockId, null, null, products);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
