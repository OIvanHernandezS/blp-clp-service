package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.vertex;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListMapper;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1.ProductsListResponse;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;

/**
 * Eager {@link BlockResolver} strategy for Vertex-backed {@code products_list} carousels: adapts the
 * core's resolution context to the block's {@link ProductsListService} (reusing the Vertex circuit
 * breaker) and maps the result to the block's REST DTO, so the inlined eager detail is identical to the
 * lazy response.
 */
@Component
public class ProductsListVertexBlockResolver implements BlockResolver {

    private final ProductsListService productsListService;
    private final ProductsListMapper productsListMapper;

    /**
     * @param productsListService the block's use case (applies the Vertex circuit breaker)
     * @param productsListMapper  maps the resolution to the block's REST DTO
     */
    public ProductsListVertexBlockResolver(ProductsListService productsListService,
                                           ProductsListMapper productsListMapper) {
        this.productsListService = productsListService;
        this.productsListMapper = productsListMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Claims the {@code products_list} shape with {@code source_of_data: vertex} (ADR-021), plus
     * legacy {@code groupby}-tagged entries aliased to this same provider (ADR-055).</p>
     */
    @Override
    public Set<BlockResolutionKey> resolutionKeys() {
        return Set.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY));
    }

    /**
     * {@inheritDoc}
     *
     * @param context block id and its raw CMS attributes ({@code products_data.category})
     * @return the resolved carousel wrapped for inlining, or {@code null} to drop an empty carousel
     *         rather than render an empty shell (legacy parity:
     *         {@code product_list-vertex.strategy.ts} returns undefined)
     */
    @Override
    public ResolvedContent resolve(BlockResolutionContext context) {
        VertexSettings settings = VertexSettings.from(context.attributes());
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
