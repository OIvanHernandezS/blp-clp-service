package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;

/**
 * Per-block Jewel settings authored in Contentstack ({@code jewel_model_config} plus the block's
 * {@code min_of_products}/{@code max_of_products}).
 *
 * <p>These are <strong>block</strong> settings, not service settings: a live landing page carries several
 * Jewel carousels driven by different models with different identity requirements
 * ({@code global-tps-sales}, {@code global-top-selling-long-model}, {@code recently-viewed-items-global},
 * {@code RA2_prod}). Resolving them all against a single configured default model returns nothing,
 * which is exactly what ms-clp-blp did before this record existed.</p>
 *
 * @param model            Jewel model name; resolution is impossible without it
 * @param requiresUserId   whether the model needs a Jewel tracking user id to return anything
 * @param requiresDeviceId whether the model needs a Jewel tracking device id to return anything
 * @param minItems         minimum items to request, from {@code min_of_products}
 * @param maxItems         maximum placements to request, from {@code max_of_products}
 */
public record JewelModelConfig(
        String model,
        boolean requiresUserId,
        boolean requiresDeviceId,
        int minItems,
        int maxItems) {

    private static final String ATTR_MODEL_CONFIG = "jewel_model_config";
    private static final String ATTR_MODEL = "jewel_model";
    private static final String ATTR_REQUIRES_USER_ID = "jewel_requires_user_id";
    private static final String ATTR_REQUIRES_DEVICE_ID = "jewel_requires_device_id";
    private static final String ATTR_MIN_OF_PRODUCTS = "min_of_products";
    private static final String ATTR_MAX_OF_PRODUCTS = "max_of_products";

    /**
     * Reads the config out of a block's raw CMS attributes.
     *
     * <p>Returns {@code null} when {@code jewel_model_config} is absent — mirroring the legacy
     * strategy, which drops a Jewel block with no model config rather than guessing (Rule 19
     * reference: {@code jewel_product_recommendation.strategy.ts}).</p>
     *
     * @param attributes raw block attributes from Contentstack
     * @param fallbackMin minimum to use when the block omits {@code min_of_products}
     * @param fallbackMax maximum to use when the block omits {@code max_of_products}
     * @return the parsed config, or {@code null} when the block carries none
     */
    public static JewelModelConfig from(Map<String, Object> attributes, int fallbackMin, int fallbackMax) {
        if (attributes == null || !(attributes.get(ATTR_MODEL_CONFIG) instanceof Map<?, ?> raw)) {
            return null;
        }
        return new JewelModelConfig(
                raw.get(ATTR_MODEL) instanceof String model ? model : null,
                BlockAttributes.bool(raw.get(ATTR_REQUIRES_USER_ID)),
                BlockAttributes.bool(raw.get(ATTR_REQUIRES_DEVICE_ID)),
                BlockAttributes.integer(attributes, ATTR_MIN_OF_PRODUCTS, fallbackMin),
                BlockAttributes.integer(attributes, ATTR_MAX_OF_PRODUCTS, fallbackMax));
    }

    /**
     * Projects the same attributes {@link #from} reads into the {@code ms_params} the frontend re-posts.
     *
     * <p>Deliberately sits beside {@code from} and reuses its constants, so the two cannot drift.</p>
     *
     * <p>Two shapes to keep straight, both mirroring {@code from}: the three model flags live
     * <em>inside</em> {@code jewel_model_config}, while {@code min_of_products}/{@code max_of_products}
     * are read from the block's <strong>top level</strong>. When the block carries no
     * {@code jewel_model_config} the projection omits it, which is exactly how {@code from} reports an
     * unresolvable Jewel block; and when a min/max is absent it stays absent, so the service-level
     * {@code JewelProperties} fallbacks still apply.</p>
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
        if (attributes.get(ATTR_MODEL_CONFIG) instanceof Map<?, ?> raw) {
            Map<String, Object> modelConfig = new LinkedHashMap<>();
            Map<String, Object> source = (Map<String, Object>) raw;
            BlockAttributes.copyIfPresent(modelConfig, source, ATTR_MODEL);
            BlockAttributes.copyIfPresent(modelConfig, source, ATTR_REQUIRES_USER_ID);
            BlockAttributes.copyIfPresent(modelConfig, source, ATTR_REQUIRES_DEVICE_ID);
            params.put(ATTR_MODEL_CONFIG, modelConfig);
        }
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MIN_OF_PRODUCTS);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MAX_OF_PRODUCTS);
        return params;
    }

    /**
     * @return {@code true} when no model name is configured, so Jewel cannot be queried
     */
    public boolean hasNoModel() {
        return model == null || model.isBlank();
    }
}
