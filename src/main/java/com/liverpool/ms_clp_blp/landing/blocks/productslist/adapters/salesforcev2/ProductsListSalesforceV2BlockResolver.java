package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

/**
 * Eager {@link BlockResolver} strategy for Salesforce Personalization (V2) {@code products_list}
 * carousels: adapts the core's resolution context to the block's {@link ProductsListService} (reusing
 * the Salesforce V2 circuit breaker, kill switch and A/B rule) and maps the result to the block's REST
 * DTO, so the inlined eager detail is identical to the lazy response.
 */
@Component
public class ProductsListSalesforceV2BlockResolver implements BlockResolver {

    private final ProductsListService productsListService;
    private final ProductsListMapper productsListMapper;
    private final SalesforceV2Properties salesforceV2Properties;

    /**
     * @param productsListService    the block's use case (applies the Salesforce V2 circuit breaker)
     * @param productsListMapper     maps the resolution to the block's REST DTO
     * @param salesforceV2Properties supplies the configurable identity header names, read here rather
     *                               than hardcoded so the eager path and the endpoint stay in step
     */
    public ProductsListSalesforceV2BlockResolver(ProductsListService productsListService,
                                                 ProductsListMapper productsListMapper,
                                                 SalesforceV2Properties salesforceV2Properties) {
        this.productsListService = productsListService;
        this.productsListMapper = productsListMapper;
        this.salesforceV2Properties = salesforceV2Properties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the {@code products_list} shape with {@code source_of_data: salesforce_v2}. Salesforce
     * V1 carousels share this content type but belong to {@code ProductsListSalesforceBlockResolver}.</p>
     */
    @Override
    public Set<BlockResolutionKey> resolutionKeys() {
        return Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2));
    }

    /**
     * {@inheritDoc}
     *
     * @param context block id, session and CMS attributes. Identity is the {@code x-sf-user-id} /
     *                {@code x-sf-device-id} headers on the session's propagated header snapshot
     * @return the resolved carousel wrapped for inlining, or {@code null} to drop an empty carousel
     *         rather than render an empty shell
     */
    @Override
    public ResolvedContent resolve(BlockResolutionContext context) {
        SessionContext session = context.session();
        SalesforceV2Settings settings = SalesforceV2Settings.from(
                context.attributes(),
                session == null ? null : session.header(salesforceV2Properties.userIdHeader()),
                session == null ? null : session.header(salesforceV2Properties.deviceIdHeader()));

        ProductsListQuery query = new ProductsListQuery(context.blockId(), session, settings,
                ConditionalShowHide.from(context.attributes()));
        ProductsListResolution resolution = productsListService.resolve(query);
        if (resolution.isEmpty()) {
            return null;
        }
        ProductsListResponse response = productsListMapper.toResponse(resolution);
        return new ResolvedContent(response);
    }
}
