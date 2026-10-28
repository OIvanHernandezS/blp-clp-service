package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Per-block Vertex AI settings authored in Contentstack.
 *
 * <p>Vertex-backed carousels resolve a category search against {@code ms_plp_search}'s
 * {@code POST /v2/search} (its Vertex AI pipeline — ADR-021), keyed on a category id rather than a
 * user identity, so there is no personalisation and no session requirement to check.</p>
 *
 * <p>Owns the CMS field names so the eager resolver and the lazy endpoint cannot drift apart (Rule 19
 * reference: {@code product_list-vertex.strategy.ts} in digital_bff reads {@code products_data.category}
 * — a nested object, unlike the other providers' flat block attributes).</p>
 *
 * @param categoryId    the block's {@code products_data.category}; {@code null} when absent, which
 *                      makes the block unresolvable
 * @param minOfProducts fewest products worth rendering; {@code null} when absent
 * @param maxOfProducts most products to render; {@code null} when absent
 */
public record VertexSettings(
        String categoryId,
        Integer minOfProducts,
        Integer maxOfProducts) implements ProviderSettings {

    private static final String ATTR_PRODUCTS_DATA = "products_data";
    private static final String ATTR_CATEGORY = "category";
    private static final String ATTR_MIN_OF_PRODUCTS = "min_of_products";
    private static final String ATTR_MAX_OF_PRODUCTS = "max_of_products";

    @Override
    public SourceOfData source() {
        return SourceOfData.VERTEX;
    }

    /**
     * Reads the settings out of a block's raw CMS attributes.
     *
     * @param attributes raw block attributes from Contentstack; may be {@code null}
     * @return the parsed settings, never {@code null} (fields are {@code null} when absent)
     */
    @SuppressWarnings("unchecked")
    public static VertexSettings from(Map<String, Object> attributes) {
        if (attributes == null) {
            return new VertexSettings(null, null, null);
        }
        Object productsData = attributes.get(ATTR_PRODUCTS_DATA);
        String categoryId = productsData instanceof Map<?, ?>
                ? BlockAttributes.string((Map<String, Object>) productsData, ATTR_CATEGORY)
                : null;
        return new VertexSettings(
                categoryId,
                BlockAttributes.integer(attributes, ATTR_MIN_OF_PRODUCTS),
                BlockAttributes.integer(attributes, ATTR_MAX_OF_PRODUCTS));
    }

    /**
     * Projects the same attributes {@link #from} reads into the {@code ms_params} the frontend re-posts.
     *
     * <p>Deliberately sits beside {@code from} and reuses its constants, so the two cannot drift.</p>
     *
     * <p>{@code products_data} is the one nested case: the CMS object also carries {@code brandId} and
     * whatever else an author adds, but only {@code category} is ever read, so the projection rebuilds
     * the wrapper with that single key. The nesting is preserved because {@code from} looks the value up
     * through it — the shape stays parseable, only the unread siblings are dropped.</p>
     *
     * @param attributes raw block attributes from Contentstack; may be {@code null}
     * @return the endpoint's parameters, mutable so the caller can add the block-level keys
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> paramsFrom(Map<String, Object> attributes) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (attributes == null) {
            return params;
        }
        if (attributes.get(ATTR_PRODUCTS_DATA) instanceof Map<?, ?> productsData) {
            Map<String, Object> projected = new LinkedHashMap<>();
            BlockAttributes.copyIfPresent(projected, (Map<String, Object>) productsData, ATTR_CATEGORY);
            if (!projected.isEmpty()) {
                params.put(ATTR_PRODUCTS_DATA, projected);
            }
        }
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MIN_OF_PRODUCTS);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MAX_OF_PRODUCTS);
        return params;
    }
}
