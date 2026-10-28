package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.config.properties.ApiJewelConfig;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.jewel.JewelProduct;
import com.liverpool.ms_clp_blp.clients.jewel.JewelClient;
import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import feign.FeignException;

/**
 * Outbound adapter resolving {@code products_list} blocks whose {@code source_of_data} is {@code jewel},
 * against the JewelML recommendation engine (Rule 19 reference:
 * {@code jewel_product_recommendation.strategy.ts} in digital_bff — note that is a <em>filename</em>, not
 * a content type; see ADR-029). The real wire contract — endpoint shape, per-brand integration-id
 * resolution, and response fields — is reused as-is from {@code platform-commons}'
 * {@code JewelClient}/{@code ApiJewelConfig}/{@code JewelProduct} (already proven in
 * {@code ms_plp_search} production) instead of being duplicated (ADR-013).
 *
 * <p>Calls {@code GET {baseUrl}/c/p/{integrationId}/l} with the model/min/max/user/device query
 * parameters. The circuit breaker is applied at the use-case layer — this adapter intentionally has no
 * retry logic (ADR-004).</p>
 *
 * <h3>Missing identity:</h3>
 * <p>A model that needs a user or device id the session cannot supply is reported through
 * {@link #skipReason}, so the block resolves to nothing without a call and without consuming a breaker
 * permit — consistent with the BFF's {@code shouldPopulate} guard (ADR-022, ADR-029).</p>
 */
@Component
public class ProductsListJewelAdapter implements ProductsListPort {

    private static final Logger log = LoggerFactory.getLogger(ProductsListJewelAdapter.class);

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = "jewel";

    private final JewelClient jewelClient;
    private final ApiJewelConfig apiJewelConfig;
    private final JewelProperties properties;
    private final JewelProductMapper productMapper = new JewelProductMapper();

    /**
     * @param jewelClient    declarative client bound to the Jewel base URL
     * @param apiJewelConfig reused connection + per-brand integration-id settings
     * @param properties     ms-clp-blp-local block behaviour settings (headers, min/max fallback, item variants)
     */
    public ProductsListJewelAdapter(JewelClient jewelClient,
                                    ApiJewelConfig apiJewelConfig,
                                    JewelProperties properties) {
        this.jewelClient = jewelClient;
        this.apiJewelConfig = apiJewelConfig;
        this.properties = properties;
    }

    @Override
    public SourceOfData source() {
        return SourceOfData.JEWEL;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolvable only when the block itself authored a {@code jewel_model_config} and every identity
     * that model declares as required is present on the request. There is no service-level model
     * fallback: every call carries the block's own CMS content (ADR-024), so a block with no model config
     * is exactly the legacy "nothing to resolve" case, not a gap to paper over (ADR-029). Each rejection
     * is a business case, not an outage.</p>
     */
    @Override
    public BlockDropReason skipReason(ProductsListQuery query) {
        JewelSettings settings = settingsOf(query);
        JewelModelConfig config = settings.modelConfig();

        if (config == null || config.hasNoModel()) {
            return logSkip(query, BlockDropReason.MISSING_MODEL_CONFIG, null, null,
                    "Block authors no Jewel model; nothing to ask Jewel for");
        }
        // Legacy parity: a model that needs an identity the session cannot supply resolves to nothing
        // rather than failing — the caller drops the block, as shouldPopulate() does in the BFF.
        if (config.requiresUserId() && isBlank(settings.userId())) {
            return logSkip(query, BlockDropReason.MISSING_USER_IDENTITY, config.model(),
                    properties.userIdHeader(),
                    "Jewel model requires a user id and the session carries none");
        }
        if (config.requiresDeviceId() && isBlank(settings.deviceId())) {
            return logSkip(query, BlockDropReason.MISSING_DEVICE_IDENTITY, config.model(),
                    properties.deviceIdHeader(),
                    "Jewel model requires a device id and the session carries none");
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>A Jewel model that requires neither a user nor a device id returns the same global
     * recommendations for every caller, so its response is edge-cacheable. A block with no model at all
     * never calls Jewel ({@link #skipReason} rejects it first), so it carries nothing session-specific
     * either.</p>
     */
    @Override
    public boolean sessionIndependent(ProductsListQuery query) {
        JewelModelConfig config = settingsOf(query).modelConfig();
        return config == null || (!config.requiresUserId() && !config.requiresDeviceId());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only ever called after {@link #skipReason} cleared the model check.</p>
     *
     * @throws DynamicBlockServiceUnavailableException when the Jewel call fails
     */
    @Override
    public ProductsListResolution fetch(ProductsListQuery query) {
        JewelSettings settings = settingsOf(query);
        JewelModelConfig config = settings.modelConfig();
        String integrationId = apiJewelConfig.resolveIntegrationId(query.session().brand());

        List<JewelProduct> response;
        try {
            // Identity parameters are sent only when the model actually asks for them — a model that
            // does not declare jewel_requires_user_id gets no user_id even if the request carried one,
            // so an anonymous-but-tracked visitor still receives the model's global results (Rule 19
            // reference: jewel.provider.ts). Feign omits a null query parameter entirely.
            response = jewelClient.getRecommendations(
                    integrationId,
                    integrationId,
                    config.model(),
                    config.minItems(),
                    config.maxItems(),
                    config.requiresUserId() ? blankToNull(settings.userId()) : null,
                    config.requiresDeviceId() ? blankToNull(settings.deviceId()) : null);
        } catch (FeignException e) {
            // Covers both HTTP error statuses and transport failures: feign.RetryableException, which
            // wraps connect/read timeouts, is itself a FeignException. e.status() is 0 for the latter,
            // which is what distinguishes "Jewel said no" from "Jewel never answered".
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    UPSTREAM,
                    e.status(),
                    "Jewel call failed for model=" + config.model() + " integrationId=" + integrationId
                            + ": " + e.getMessage(),
                    e);
        }

        return mapResponse(query.blockId(), response);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when routed a query for another provider; defensive only, since
     *                                 the use case routes by {@link #source()}
     */
    private JewelSettings settingsOf(ProductsListQuery query) {
        if (query.provider() instanceof JewelSettings settings) {
            return settings;
        }
        throw new IllegalArgumentException(
                "ProductsListJewelAdapter cannot serve source_of_data=" + query.source());
    }

    /** Jewel carries no campaign title or id, so those stay {@code null} and the REST mapper omits them. */
    private ProductsListResolution mapResponse(String blockId, List<JewelProduct> response) {
        if (response == null || response.isEmpty()) {
            LogEvents.at(log, LogEvents.UPSTREAM_NO_PRODUCTS)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BLOCK_ID, blockId)
                    .addKeyValue(LogFields.SOURCE, SourceOfData.JEWEL)
                    .addKeyValue(LogFields.REASON, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS)
                    .log("Jewel answered successfully but returned no products");
            return ProductsListResolution.empty(blockId, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
        }

        List<ProductRecommendation> products =
                productMapper.toProducts(response, properties.includeItemVariants());

        LogEvents.at(log, LogEvents.BLOCK_RESOLVED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, blockId)
                .addKeyValue(LogFields.SOURCE, SourceOfData.JEWEL)
                .addKeyValue(LogFields.PRODUCT_COUNT, products.size())
                .log("Jewel resolved the block");
        return ProductsListResolution.available(blockId, null, null, products);
    }

    /**
     * Reports a call this session could never have satisfied. DEBUG rather than WARN: every one of
     * these is a business no-op — the block simply has nothing to show this visitor — and mixing them
     * into the warning stream is what made a missing Jewel model look like an incident while the
     * identical Vertex and Salesforce cases stayed silent.
     *
     * @return {@code reason}, so the ladder in {@link #skipReason} logs and answers in one statement and
     *         the two can never disagree about which rule fired
     */
    private BlockDropReason logSkip(ProductsListQuery query, BlockDropReason reason, String model,
                                    String missingHeader, String message) {
        LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                .addKeyValue(LogFields.SOURCE, SourceOfData.JEWEL)
                .addKeyValue(LogFields.REASON, reason)
                .addKeyValue(LogFields.MODEL, model)
                .addKeyValue(LogFields.MISSING_HEADER, missingHeader)
                .log(message);
        return reason;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Feign drops a {@code null} query parameter but would send a blank one as {@code param=}. */
    private static String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }
}
