package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.liverpool.constant.SalesforceV2Constants;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.v2.SalesforceV2Request;
import com.liverpool.dto.salesforce.v2.SalesforceV2Response;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Client;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
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
 * {@code salesforce_v2} against the Salesforce Personalization (V2) decisions API.
 *
 * <p>Speaks the same wire contract as commons' {@code SalesforceV2ApiCaller}, which backs the
 * equivalent carousels in {@code ms_pdp_search} and {@code ms_plp_search}: one personalization point
 * per call, named by the block's {@code salesforce_v2_config.model}. It does not reuse that caller
 * because its annotation-driven circuit breaker needs Spring AOP, which this service does not run, and
 * because its fallback swallows every failure into an empty list — here the breaker is applied
 * programmatically by the use case and a failure must surface as
 * {@link DynamicBlockServiceUnavailableException} so the block reports {@code UNAVAILABLE} rather than
 * a misleading {@code EMPTY} (ADR-004, ADR-043).</p>
 *
 * <h3>Identity</h3>
 * <p>Header passthrough in parity with commons: {@code individualId} is the logged-in id when the request
 * carries one and the device id otherwise. Whether either is <em>required</em> is authored per block
 * ({@code requires_user_id} / {@code requires_device_id}); unlike Salesforce V1 (ADR-041) there is no
 * {@code SessionId} gate, so guests are served through their device id.</p>
 *
 * <h3>What this adapter does not do</h3>
 * <p>The feature flag, the A/B {@code conditional_show_hide} rule and the {@code min_of_products} /
 * {@code max_of_products} thresholds are applied by {@code ProductsListUseCases} for every provider
 * alike, and {@code audience_filter} / channel gating at composition time — so none is repeated
 * here.</p>
 */
@Component
public class ProductsListSalesforceV2Adapter implements ProductsListPort {

    private static final Logger log = LoggerFactory.getLogger(ProductsListSalesforceV2Adapter.class);

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = SalesforceV2Constants.SOURCE_OF_DATA;

    private final SalesforceV2ProductMapper productMapper = new SalesforceV2ProductMapper();
    private final SalesforceV2Client salesforceV2Client;
    private final SalesforceV2Properties properties;

    /**
     * @param salesforceV2Client typed client for the decisions endpoint, on commons' isolated transport
     * @param properties         the identity header names, reported when a required one is missing
     */
    public ProductsListSalesforceV2Adapter(SalesforceV2Client salesforceV2Client,
                                           SalesforceV2Properties properties) {
        this.salesforceV2Client = salesforceV2Client;
        this.properties = properties;
    }

    @Override
    public SourceOfData source() {
        return SourceOfData.SALESFORCE_V2;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The drop ladder of commons' {@code shouldPopulate}, minus the gates ms-clp-blp enforces centrally.
     * Every check is pure, so a block that resolves to nothing costs neither an HTTP call nor a breaker
     * permit.</p>
     */
    @Override
    public BlockDropReason skipReason(ProductsListQuery query) {
        SalesforceV2Settings settings = settingsOf(query);
        if (settings.hasNoModel()) {
            return logSkip(query, BlockDropReason.MISSING_MODEL_CONFIG, null, null,
                    "Block authors no salesforce_v2_config model; there is no personalization point to ask for");
        }
        if (settings.requiresItemId()) {
            // Commons declines such a point when the request carries no productId. Home has no product
            // context to anchor to, so the point can never be satisfied here.
            return logSkip(query, BlockDropReason.MISSING_ANCHOR_PRODUCT, settings.model(), null,
                    "Salesforce V2 point recommends against an anchor product, and a landing page has none");
        }
        if (settings.requiresUserId() && isBlank(settings.userId())) {
            return logSkip(query, BlockDropReason.MISSING_USER_IDENTITY, settings.model(),
                    properties.userIdHeader(),
                    "Salesforce V2 point requires a user id and the request carries none");
        }
        if (settings.requiresDeviceId() && isBlank(settings.deviceId())) {
            return logSkip(query, BlockDropReason.MISSING_DEVICE_IDENTITY, settings.model(),
                    properties.deviceIdHeader(),
                    "Salesforce V2 point requires a device id and the request carries none");
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code false}: Salesforce decides per individual, so no V2 response may be cached at the
     * edge.</p>
     */
    @Override
    public boolean sessionIndependent(ProductsListQuery query) {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @throws DynamicBlockServiceUnavailableException when the Salesforce V2 call fails
     */
    @Override
    public ProductsListResolution fetch(ProductsListQuery query) {
        SalesforceV2Settings settings = settingsOf(query);

        SalesforceV2Response response;
        try {
            response = salesforceV2Client.getDecisions(buildRequest(settings)).getBody();
        } catch (FeignException e) {
            // Covers HTTP error statuses and transport failures alike. e.status() is 0 for the latter,
            // which is what distinguishes "Salesforce said no" from "Salesforce never answered".
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    UPSTREAM,
                    e.status(),
                    "Salesforce V2 call failed for model=" + settings.model() + ": " + e.getMessage(),
                    e);
        }

        return mapResponse(query.blockId(), settings.model(), response);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when routed a query for another provider; defensive only, since
     *                                 the use case routes by {@link #source()}
     */
    private SalesforceV2Settings settingsOf(ProductsListQuery query) {
        if (query.provider() instanceof SalesforceV2Settings settings) {
            return settings;
        }
        throw new IllegalArgumentException(
                "ProductsListSalesforceV2Adapter cannot serve source_of_data=" + query.source());
    }

    /**
     * One personalization point per call, as commons sends it. No anchor: a point that needs one never
     * gets this far. The correlation id is the request's trace id, read from the same MDC key commons
     * uses, so a call can be found on both sides.
     */
    private SalesforceV2Request buildRequest(SalesforceV2Settings settings) {
        return new SalesforceV2Request(
                new SalesforceV2Request.SalesforceV2Context(
                        settings.individualId(), null, MDC.get(SalesforceV2Constants.TRACE_ID_MDC_KEY)),
                List.of(new SalesforceV2Request.PersonalizationPoint(settings.model())));
    }

    /**
     * Projects the personalization decided for the requested point.
     *
     * <p>When several come back, the one whose name matches the model wins, and one Salesforce left
     * unnamed is accepted — the same rule as commons' {@code SalesforceV2ApiCaller}.</p>
     */
    private ProductsListResolution mapResponse(String blockId, String model, SalesforceV2Response response) {
        Optional<SalesforceV2Response.Personalization> decided = response == null
                || response.getPersonalizations() == null
                ? Optional.empty()
                : response.getPersonalizations().stream()
                        .filter(Objects::nonNull)
                        .filter(personalization -> matchesModel(personalization, model))
                        .filter(personalization -> personalization.getData() != null)
                        .findFirst();
        if (decided.isEmpty()) {
            return logNoProducts(blockId, model, "Salesforce V2 answered with no decision for the requested point");
        }

        SalesforceV2Response.Personalization personalization = decided.get();
        List<ProductRecommendation> products =
                productMapper.toProducts(personalization.getData(), personalization.getPersonalizationId());
        if (products.isEmpty()) {
            return logNoProducts(blockId, model, "Salesforce V2 decided the point but returned no products");
        }

        LogEvents.at(log, LogEvents.BLOCK_RESOLVED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, blockId)
                .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE_V2)
                .addKeyValue(LogFields.MODEL, model)
                .addKeyValue(LogFields.PRODUCT_COUNT, products.size())
                .log("Salesforce V2 resolved the block");
        // V2 carries no carousel title; the point name plays the role of V1's campaign id.
        return ProductsListResolution.available(blockId, null, model, products);
    }

    private static boolean matchesModel(SalesforceV2Response.Personalization personalization, String model) {
        String name = personalization.getPersonalizationPointName();
        return name == null || name.equalsIgnoreCase(model);
    }

    /**
     * Reports a successful call that yielded nothing to render.
     *
     * @return the matching empty resolution, so the logged {@code reason} and the one the endpoint
     *         returns are the same value read from the same place (ADR-043)
     */
    private ProductsListResolution logNoProducts(String blockId, String model, String message) {
        LogEvents.at(log, LogEvents.UPSTREAM_NO_PRODUCTS)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, blockId)
                .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE_V2)
                .addKeyValue(LogFields.MODEL, model)
                .addKeyValue(LogFields.REASON, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS)
                .log(message);
        return ProductsListResolution.empty(blockId, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
    }

    /**
     * Reports a call this request could never have satisfied. DEBUG-level business no-op, like the
     * other providers' skips.
     *
     * @return {@code reason}, so the ladder in {@link #skipReason} logs and answers in one statement
     */
    private BlockDropReason logSkip(ProductsListQuery query, BlockDropReason reason, String model,
                                    String missingHeader, String message) {
        LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE_V2)
                .addKeyValue(LogFields.REASON, reason)
                .addKeyValue(LogFields.MODEL, model)
                .addKeyValue(LogFields.MISSING_HEADER, missingHeader)
                .log(message);
        return reason;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
