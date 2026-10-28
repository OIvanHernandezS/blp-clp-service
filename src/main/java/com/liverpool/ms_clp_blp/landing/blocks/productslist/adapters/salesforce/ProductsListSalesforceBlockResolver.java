package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforce;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;

/**
 * Eager {@link BlockResolver} strategy for Salesforce-backed {@code products_list} carousels: adapts the
 * core's resolution context to the block's {@link ProductsListService} (reusing the Salesforce circuit
 * breaker) and maps the result to the block's REST DTO, so the inlined eager detail is identical to the
 * lazy response.
 */
@Component
public class ProductsListSalesforceBlockResolver implements BlockResolver {

    private final ProductsListService productsListService;
    private final ProductsListMapper productsListMapper;
    private final SalesforceProperties salesforceProperties;

    /**
     * @param productsListService  the block's use case (applies the Salesforce circuit breaker)
     * @param productsListMapper   maps the resolution to the block's REST DTO
     * @param salesforceProperties supplies the configurable {@code x-sf-user-id} header name, read here
     *                             rather than hardcoded so the eager path and the endpoint stay in step
     */
    public ProductsListSalesforceBlockResolver(ProductsListService productsListService,
                                               ProductsListMapper productsListMapper,
                                               SalesforceProperties salesforceProperties) {
        this.productsListService = productsListService;
        this.productsListMapper = productsListMapper;
        this.salesforceProperties = salesforceProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the Salesforce-backed {@code products_list} blocks plus legacy entries that carry no
     * {@code source_of_data} at all, which historically routed here. Jewel-backed carousels share this
     * content type but belong to {@code ProductsListJewelBlockResolver}.</p>
     */
    @Override
    public Set<BlockResolutionKey> resolutionKeys() {
        return Set.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE));
    }

    /**
     * {@inheritDoc}
     *
     * @param context block id, session and CMS attributes. Identity is the {@code x-sf-user-id} header
     *                carried on the session's propagated header snapshot, gated on the {@code SessionId}
     *                cookie having travelled (ADR-041 — the documented exception to ADR-033, which still
     *                governs every other Salesforce block)
     * @return the resolved product carousel wrapped for inlining, or {@code null} to drop an empty
     *         carousel rather than render an empty shell (legacy parity:
     *         {@code product_list-salesforce.strategy.ts} returns undefined)
     */
    @Override
    public ResolvedContent resolve(BlockResolutionContext context) {
        String userId = SalesforceSettings.resolveUserId(
                context.session(), salesforceProperties.userIdHeader());
        SalesforceSettings settings = SalesforceSettings.from(context.attributes(), userId);

        ProductsListQuery query = new ProductsListQuery(context.blockId(), context.session(), settings,
                ConditionalShowHide.from(context.attributes()));
        ProductsListResolution resolution = productsListService.resolve(query);
        if (resolution.isEmpty()) {
            return null;
        }
        ProductsListResponse response = productsListMapper.toResponse(resolution);
        return new ResolvedContent(response);
    }
}
