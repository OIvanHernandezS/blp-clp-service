package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters;

import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListParams;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockParamsBuilder;

/**
 * Publishes the {@code ms_params} projection for every {@code products_list} carousel, whichever
 * provider backs it.
 *
 * <p>Lives at the slice's {@code adapters} root rather than under a provider package because it spans
 * every routing key — the per-provider knowledge sits in the settings records that own those CMS
 * field names, and {@link ProductsListParams} dispatches to them.</p>
 */
@Component
public class ProductsListParamsBuilder implements BlockParamsBuilder {

    /**
     * {@inheritDoc}
     *
     * <p>Claims the same six keys the catalog maps to this endpoint — the four explicit sources,
     * {@link SourceOfData#GROUPBY} aliased to the Vertex provider (ADR-055), plus
     * {@link SourceOfData#NONE} for legacy entries that predate {@code source_of_data} and keep their
     * historical Salesforce routing. A key missing here would advertise an empty {@code ms_params} for a
     * body-driven endpoint, so {@code BlockResolutionContractTest} pins the set.</p>
     */
    @Override
    public Set<BlockResolutionKey> paramKeys() {
        return Set.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE));
    }

    @Override
    public Map<String, Object> paramsFor(String blockId, Map<String, Object> attributes) {
        return ProductsListParams.of(blockId, attributes);
    }
}
