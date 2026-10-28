package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ProductsListProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListParams;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProviderSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;

/**
 * Inbound adapter for the {@code products_list} block's resolution endpoint — the single endpoint for
 * every provider that backs the block (ADR-029).
 *
 * <p><strong>Contract:</strong> {@code POST /blp/blocks/products-list} (or {@code /clp/…}) with a JSON <em>array</em> of the
 * block objects the caller already received from the landing page ({@code GET /blp/**}, {@code GET /clp/**}). One block is an array of one; there
 * is no separate single-block route, so there is one contract and one code path.</p>
 *
 * <p><strong>The body is what makes resolution possible.</strong> Each block carries its own
 * {@code source_of_data} — which decides the provider — plus the settings that provider needs
 * ({@code jewel_model_config}, {@code salesforce_carousel}, {@code salesforce_v2_config},
 * {@code min_of_products}/{@code max_of_products}). A landing page may run several carousels from different models and
 * campaigns, so a request without that context could only fall back to service defaults and would
 * resolve the wrong campaign or nothing at all (ADR-022, ADR-024). This replaces the previous
 * per-provider endpoints and their deprecated context-free {@code GET} forms.</p>
 *
 * <p>Blocks are resolved <strong>concurrently</strong>, one virtual thread each, so the frontend spends
 * one round trip and the server spends the latency of the slowest provider rather than the sum. Each
 * entry carries its own {@code status}: a provider outage degrades its own carousels and the rest of the
 * batch still resolves, so this endpoint answers 200 with partial content instead of failing the page's
 * whole resolution call. Only a total failure surfaces as an RFC 7807 problem through
 * {@code GlobalExceptionHandler}.</p>
 */
@RestController
@RequestMapping({"/blp/blocks/products-list", "/clp/blocks/products-list"})
@Validated
public class ProductsListControllerV1 implements ProductsListApiV1 {

    private static final Logger log = LoggerFactory.getLogger(ProductsListControllerV1.class);

    // Owned by ProductsListParams, which projects them into every block's advertised `ms_params`. One
    // owner keeps the endpoint and the projection reading the same two keys.
    static final String ATTR_UID = ProductsListParams.ATTR_UID;
    static final String ATTR_SOURCE_OF_DATA = ProductsListParams.ATTR_SOURCE_OF_DATA;
    static final String HEADER_REQUEST_ID = "x-request-id";

    private final ProductsListService productsListService;
    private final SessionContextPort sessionContextPort;
    private final ProductsListMapper productsListMapper;
    private final JewelProperties jewelProperties;
    private final SalesforceProperties salesforceProperties;
    private final SalesforceV2Properties salesforceV2Properties;
    private final ProductsListProperties properties;

    /**
     * @param productsListService resolves the carousels behind each provider's circuit breaker
     * @param sessionContextPort  resolves the session from upstream-validated headers
     * @param productsListMapper  converts domain resolutions to the REST envelope
     * @param jewelProperties     supplies the configurable Jewel tracking header names and defaults
     * @param salesforceProperties supplies the configurable {@code x-sf-user-id} header name (ADR-041)
     * @param salesforceV2Properties supplies the configurable Salesforce V2 identity header names
     * @param properties          endpoint behaviour settings (edge cache TTL)
     */
    public ProductsListControllerV1(ProductsListService productsListService,
                                    SessionContextPort sessionContextPort,
                                    ProductsListMapper productsListMapper,
                                    JewelProperties jewelProperties,
                                    SalesforceProperties salesforceProperties,
                                    SalesforceV2Properties salesforceV2Properties,
                                    ProductsListProperties properties) {
        this.productsListService = productsListService;
        this.sessionContextPort = sessionContextPort;
        this.productsListMapper = productsListMapper;
        this.jewelProperties = jewelProperties;
        this.salesforceProperties = salesforceProperties;
        this.salesforceV2Properties = salesforceV2Properties;
        this.properties = properties;
    }

    /**
     * Resolves every requested {@code products_list} carousel for the current session.
     *
     * @param blocks the blocks' CMS content as received from the landing page ({@code GET /blp/**}, {@code GET /clp/**}); each must carry a
     *               {@code uid}, and its {@code source_of_data} selects the provider
     * @return 200 with one entry per block, in request order
     */
    @PostMapping
    @Override
    public ResponseEntity<ProductsListBatchResponse> resolveProductsList(
            @RequestBody List<Map<String, Object>> blocks) {

        // No token exchange: every provider here personalises on request headers, and the Salesforce
        // V1 gate below needs only the cookie's presence, which is checked locally (ADR-054).
        SessionContext session = sessionContextPort.currentContextWithoutIdentity();
        // Salesforce V1 identity: the x-sf-user-id header, and only when a SessionId cookie travelled
        // (ADR-041). Every other Salesforce block still uses session.authenticatedProfileId() (ADR-033).
        String atgUserId =
                SalesforceSettings.resolveUserId(session, salesforceProperties.userIdHeader());

        List<ProductsListQuery> queries = new ArrayList<>(blocks.size());
        List<ProductsListResolution> unroutable = new ArrayList<>();
        List<Integer> slots = new ArrayList<>(blocks.size());

        for (Map<String, Object> block : blocks) {
            String blockId = BlockAttributes.string(block, ATTR_UID);
            ProviderSettings settings = settingsFor(block, session, atgUserId);
            if (settings == null) {
                slots.add(-(unroutable.size() + 1));
                unroutable.add(ProductsListResolution.unavailable(
                        blockId, BlockDropReason.UNSUPPORTED_SOURCE_OF_DATA));
            } else {
                slots.add(queries.size());
                queries.add(new ProductsListQuery(
                        blockId, session, settings, ConditionalShowHide.from(block)));
            }
        }

        List<ProductsListResolution> resolved = productsListService.resolveAll(queries);
        List<ProductsListResolution> ordered = merge(slots, resolved, unroutable);

        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .cacheControl(cachePolicy(queries, unroutable.isEmpty()))
                .body(productsListMapper.toBatchResponse(ordered));
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the provider settings a block needs, from its own {@code source_of_data}.
     *
     * <p>Returns {@code null} for a source this service does not implement ({@code lob},
     * {@code recently_viewed}). Such a block is reported
     * {@code UNAVAILABLE} rather than routed to whichever provider happens to share its content type —
     * the misrouting bug ADR-021 fixed. It should never arrive here anyway: an unmapped source never
     * becomes a placeholder advertising this endpoint. {@code groupby} is aliased to the Vertex provider
     * (ADR-055) rather than falling into that bucket.</p>
     */
    private ProviderSettings settingsFor(Map<String, Object> block,
                                         SessionContext session,
                                         String atgUserId) {
        SourceOfData source = SourceOfData.from(block == null ? null : block.get(ATTR_SOURCE_OF_DATA));
        return switch (source) {
            case JEWEL -> jewelSettings(block, session);
            case SALESFORCE, NONE -> SalesforceSettings.from(block, atgUserId);
            case SALESFORCE_V2 -> SalesforceV2Settings.from(block,
                    session.header(salesforceV2Properties.userIdHeader()),
                    session.header(salesforceV2Properties.deviceIdHeader()));
            case VERTEX, GROUPBY -> VertexSettings.from(block);
            case UNSUPPORTED -> null;
        };
    }

    private JewelSettings jewelSettings(Map<String, Object> block, SessionContext session) {
        return new JewelSettings(
                session.header(jewelProperties.userIdHeader()),
                session.header(jewelProperties.deviceIdHeader()),
                JewelModelConfig.from(block, jewelProperties.minItems(), jewelProperties.maxItems()));
    }

    /**
     * Reassembles request order: a non-negative slot indexes {@code resolved}, a negative one indexes
     * {@code unroutable} (encoded as {@code -(i + 1)}).
     */
    private List<ProductsListResolution> merge(List<Integer> slots,
                                               List<ProductsListResolution> resolved,
                                               List<ProductsListResolution> unroutable) {
        List<ProductsListResolution> ordered = new ArrayList<>(slots.size());
        for (int slot : slots) {
            ordered.add(slot >= 0 ? resolved.get(slot) : unroutable.get(-slot - 1));
        }
        return ordered;
    }

    /**
     * Only a response whose every block is session-independent may be cached at the edge; anything
     * personalised, or any block this service could not route, is {@code no-store}.
     */
    private CacheControl cachePolicy(List<ProductsListQuery> queries, boolean allRoutable) {
        if (allRoutable && productsListService.allSessionIndependent(queries)) {
            return CacheControl.maxAge(Duration.ofSeconds(properties.cacheSeconds())).cachePublic();
        }
        return CacheControl.noStore().cachePrivate();
    }
}
