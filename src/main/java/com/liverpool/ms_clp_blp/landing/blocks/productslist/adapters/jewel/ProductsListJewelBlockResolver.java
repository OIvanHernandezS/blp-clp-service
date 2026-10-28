package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;

/**
 * Eager {@link BlockResolver} strategy for Jewel-backed {@code products_list} carousels: adapts the
 * core's resolution context to the block's {@link ProductsListService} (reusing the Jewel circuit
 * breaker) and maps the result to the block's REST DTO, so the inlined eager detail is identical to the
 * lazy response.
 *
 * <p>Jewel identity travels in configurable headers ({@code jewel.user-id-header} /
 * {@code jewel.device-id-header}); this resolver reads them from {@link JewelProperties} so it stays
 * consistent with the resolution endpoint regardless of the configured header names.</p>
 */
@Component
public class ProductsListJewelBlockResolver implements BlockResolver {

    private final ProductsListService productsListService;
    private final ProductsListMapper productsListMapper;
    private final JewelProperties jewelProperties;

    /**
     * @param productsListService the block's use case (applies the Jewel circuit breaker)
     * @param productsListMapper  maps the resolution to the block's REST DTO
     * @param jewelProperties     supplies the configurable Jewel identity header names
     */
    public ProductsListJewelBlockResolver(ProductsListService productsListService,
                                          ProductsListMapper productsListMapper,
                                          JewelProperties jewelProperties) {
        this.productsListService = productsListService;
        this.productsListMapper = productsListMapper;
        this.jewelProperties = jewelProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the one shape the CMS actually ships for Jewel: {@code products_list} with
     * {@code source_of_data: jewel}. There is no {@code jewel_product_recommendation} content type —
     * that name is a digital_bff filename (ADR-029).</p>
     */
    @Override
    public Set<BlockResolutionKey> resolutionKeys() {
        return Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL));
    }

    /**
     * {@inheritDoc}
     *
     * @param context block id, session and propagated gateway headers (Jewel user/device ids)
     * @return the resolved recommendation carousel wrapped for inlining, or {@code null} to drop an
     *         empty carousel rather than render an empty shell (legacy parity:
     *         {@code jewel_product_recommendation.strategy.ts} returns undefined)
     */
    @Override
    public ResolvedContent resolve(BlockResolutionContext context) {
        JewelModelConfig modelConfig = JewelModelConfig.from(
                context.attributes(), jewelProperties.minItems(), jewelProperties.maxItems());
        JewelSettings settings = new JewelSettings(
                context.header(jewelProperties.userIdHeader()),
                context.header(jewelProperties.deviceIdHeader()),
                modelConfig);

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
