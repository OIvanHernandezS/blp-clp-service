package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.constant.SalesforceCarrouselsConstants;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.salesforce.SalesforceCampaignResponses;
import com.liverpool.dto.salesforce.SalesforceFlags;
import com.liverpool.dto.salesforce.SalesforceRequest;
import com.liverpool.dto.salesforce.SalesforceResponse;
import com.liverpool.dto.salesforce.SalesforceSource;
import com.liverpool.dto.salesforce.SalesforceUser;
import com.liverpool.dto.salesforce.SalesforceUserAttributes;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceCarrouselsClient;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
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
 * {@code salesforce} — and legacy blocks that carry no {@code source_of_data} at all — against the
 * Salesforce Evergage recommendation engine.
 *
 * <p>Speaks the same wire contract as {@code platform-commons}' {@code SalesforceCarrouselsApiCaller},
 * which backs the equivalent carousels in {@code ms_plp_search} and {@code ms_pdp_search}: the request
 * is built from commons' {@code com.liverpool.dto.salesforce} DTOs and every literal comes from
 * {@link SalesforceCarrouselsConstants}. The circuit breaker is applied at the use-case layer — this
 * adapter intentionally has no retry logic (ADR-004).</p>
 *
 * <h3>Identity, and sessions that cannot be personalised</h3>
 * <p>Salesforce V1 only serves logged-in shoppers, so this provider needs <strong>both</strong> a
 * {@code SessionId} cookie and an {@code x-sf-user-id} header carrying the ATG profile id it sends as
 * {@code ID_ATG1} (ADR-041 — the documented exception to ADR-033, which still governs the other three
 * Salesforce blocks). {@link SalesforceSettings#resolveUserId} owns that rule for both the eager and the
 * lazy path.</p>
 *
 * <p>Missing either one is a business case, not an outage: the carousel is reported unresolvable through
 * {@link #skipReason} and never reaches the wire, so it neither reports
 * {@code BLOCK_SERVICE_UNAVAILABLE} nor consumes a circuit-breaker permit (ADR-022, ADR-029). ms-clp-blp
 * still does <strong>not</strong> adopt commons' device-id / visitor-id fallback, which would serve
 * guests.</p>
 *
 * <h3>Response mapping</h3>
 * <p>Salesforce answers with a list of campaigns, any leading one of which may be empty. The first
 * campaign that actually yields products wins, and the carousel's title and id are read from
 * <em>that</em> campaign — reading index 0 unconditionally is how a populated carousel behind an empty
 * leading campaign came to resolve as empty. The three product shapes are handled by
 * {@link SalesforceProductMapper}.</p>
 */
@Component
public class ProductsListSalesforceAdapter implements ProductsListPort {

    private static final Logger log = LoggerFactory.getLogger(ProductsListSalesforceAdapter.class);

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = "salesforce";

    private final SalesforceProductMapper productMapper = new SalesforceProductMapper();
    private final SalesforceCarrouselsClient salesforceCarrouselsClient;
    private final SalesforceProperties properties;

    /**
     * @param salesforceCarrouselsClient typed client for Evergage's campaign-decision endpoint; carries
     *                                   the basic credentials via commons' Feign configuration
     * @param properties                 Salesforce settings; only {@code application} is read here, the
     *                                   URL and credentials belong to the client's own configuration
     */
    public ProductsListSalesforceAdapter(
            SalesforceCarrouselsClient salesforceCarrouselsClient,
            SalesforceProperties properties) {
        this.salesforceCarrouselsClient = salesforceCarrouselsClient;
        this.properties = properties;
    }

    @Override
    public SourceOfData source() {
        return SourceOfData.SALESFORCE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The drop ladder, in the order commons applies it, minus the gates ms-clp-blp already enforces
     * centrally: the feature flag lives in {@code LandingConfig}'s catalog, and {@code audience_filter} /
     * {@code enable_on_web} / {@code enable_on_apps} are enforced at composition time
     * ({@code LandingCompositionService.isVisible}). Every check here is pure, so a block that resolves to
     * nothing costs neither an HTTP call nor a breaker permit.</p>
     */
    @Override
    public BlockDropReason skipReason(ProductsListQuery query) {
        SalesforceSettings settings = settingsOf(query);
        if (isBlank(settings.userId())) {
            return logSkip(query, BlockDropReason.MISSING_USER_IDENTITY, settings.carousel(),
                    properties.userIdHeader(),
                    "Salesforce personalises per user and the session supplies neither a SessionId "
                            + "cookie nor the user-id header");
        }
        if (isBlank(settings.carousel())) {
            return logSkip(query, BlockDropReason.MISSING_CAMPAIGN, null, null,
                    "Block authors no salesforce_carousel action; there is no campaign to request");
        }
        if (isSimilarItemsAction(settings.carousel())) {
            // SimilarItems_* recommends against an anchor product. A landing page has no product context to
            // anchor to, so such a block can only ever return an empty campaign — commons declines it
            // the same way when its request carries no productId. Dropping it here keeps the call off
            // the wire instead of trading it for an HTTP 200 with no campaigns.
            return logSkip(query, BlockDropReason.MISSING_ANCHOR_PRODUCT, settings.carousel(), null,
                    "Salesforce action recommends against an anchor product, and a landing page has none");
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Always {@code false}: every Salesforce carousel is personalised on the caller's ATG profile id,
     * so no Salesforce response may be cached at the edge.</p>
     */
    @Override
    public boolean sessionIndependent(ProductsListQuery query) {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @throws DynamicBlockServiceUnavailableException when the Salesforce call fails
     */
    @Override
    public ProductsListResolution fetch(ProductsListQuery query) {
        SalesforceSettings settings = settingsOf(query);

        SalesforceResponse response;
        try {
            response = salesforceCarrouselsClient.post(buildRequest(settings)).getBody();
        } catch (FeignException e) {
            // Covers HTTP error statuses and transport failures alike. e.status() is 0 for the latter,
            // which is what distinguishes "Salesforce said no" from "Salesforce never answered".
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    UPSTREAM,
                    e.status(),
                    "Salesforce call failed for carousel=" + settings.carousel() + ": " + e.getMessage(),
                    e);
        }

        return mapResponse(query.blockId(), settings.carousel(), response);
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @throws IllegalArgumentException when routed a query for another provider; defensive only, since
     *                                 the use case routes by {@link #source()}
     */
    private SalesforceSettings settingsOf(ProductsListQuery query) {
        if (query.provider() instanceof SalesforceSettings settings) {
            return settings;
        }
        throw new IllegalArgumentException(
                "ProductsListSalesforceAdapter cannot serve source_of_data=" + query.source());
    }

    /**
     * Builds the Salesforce request for one carousel.
     *
     * <p>{@code source.url} carries the block's {@code salesforce_category_id} when it has one, which is
     * what scopes the campaign to a category; omitting it returns an unscoped campaign. The action comes
     * from the block itself — each carousel on the live page runs its own ({@code CMSOfertasIncreibles},
     * {@code CMSTePuedeGustar}, …), so there is no service-wide default to fall back to (ADR-022); a
     * block without one is dropped in {@link #skipReason}.</p>
     */
    private SalesforceRequest buildRequest(SalesforceSettings settings) {
        return SalesforceRequest.builder()
                .action(settings.carousel())
                .flags(SalesforceFlags.builder().noCampaigns(false).build())
                .source(SalesforceSource.builder()
                        .channel(SalesforceCarrouselsConstants.CHANNEL_SERVER)
                        .application(properties.application())
                        .url(trimToNull(settings.categoryId()))
                        .build())
                .user(SalesforceUser.builder()
                        .attributes(SalesforceUserAttributes.builder()
                                .ID_ATG1(settings.userId())
                                .build())
                        .build())
                .build();
    }

    /** @return true for {@code SimilarItems_1} and every other {@code SimilarItems_*} variant */
    private boolean isSimilarItemsAction(String action) {
        String prefix = SalesforceCarrouselsConstants.SIMILAR_ITEMS_ACTION_PREFIX;
        return action != null && action.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** @return the trimmed value, or {@code null} when it is absent or blank */
    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? SalesforceCarrouselsConstants.EMPTY_VALUE : value;
    }

    /**
     * Projects the first campaign that actually returned products, together with its title and id.
     *
     * @param blockId  the block being resolved, for logging and the resolution
     * @param response the Salesforce response; may be {@code null}
     * @return the resolved carousel, or an empty resolution when no campaign yielded products
     */
    private ProductsListResolution mapResponse(String blockId, String carousel,
                                               SalesforceResponse response) {
        if (response == null || response.getCampaignResponses() == null) {
            return logNoProducts(blockId, carousel,
                    "Salesforce answered with no campaignResponses at all");
        }

        for (SalesforceCampaignResponses campaign : response.getCampaignResponses()) {
            if (campaign == null || campaign.getPayload() == null) {
                continue;
            }
            List<ProductRecommendation> products = productMapper.toProducts(campaign.getPayload());
            if (products.isEmpty()) {
                continue;
            }
            String campaignName = orEmpty(campaign.getCampaignName());
            String title = orEmpty(campaign.getPayload().getTitle());
            LogEvents.at(log, LogEvents.BLOCK_RESOLVED)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BLOCK_ID, blockId)
                    .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE)
                    .addKeyValue(LogFields.CAMPAIGN, campaignName)
                    .addKeyValue(LogFields.PRODUCT_COUNT, products.size())
                    .log("Salesforce resolved the block");
            return ProductsListResolution.available(blockId, title, campaignName, products);
        }

        return logNoProducts(blockId, carousel,
                "Salesforce returned campaigns but none carried products");
    }

    /**
     * Reports a successful call that yielded nothing to render. Both shapes — no campaigns at all and
     * campaigns with empty payloads — end the same way for the reader, so they share one event and are
     * told apart by the message and by {@code campaign}.
     *
     * @return the matching empty resolution, so the logged {@code reason} and the one the endpoint
     *         returns are the same value read from the same place (ADR-043)
     */
    private ProductsListResolution logNoProducts(String blockId, String carousel, String message) {
        LogEvents.at(log, LogEvents.UPSTREAM_NO_PRODUCTS)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, blockId)
                .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE)
                .addKeyValue(LogFields.CAMPAIGN, carousel)
                .addKeyValue(LogFields.REASON, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS)
                .log(message);
        return ProductsListResolution.empty(blockId, BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
    }

    /**
     * Reports a call this session could never have satisfied. DEBUG rather than WARN: each of these is
     * a business no-op — a guest has no Salesforce identity, a block may author no carousel — and the
     * composition summary already counts the resulting drop.
     *
     * @return {@code reason}, so the ladder in {@link #skipReason} logs and answers in one statement and
     *         the two can never disagree about which rule fired
     */
    private BlockDropReason logSkip(ProductsListQuery query, BlockDropReason reason, String carousel,
                                    String missingHeader, String message) {
        LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                .addKeyValue(LogFields.SOURCE, SourceOfData.SALESFORCE)
                .addKeyValue(LogFields.REASON, reason)
                .addKeyValue(LogFields.CAMPAIGN, carousel)
                .addKeyValue(LogFields.MISSING_HEADER, missingHeader)
                .log(message);
        return reason;
    }
}
