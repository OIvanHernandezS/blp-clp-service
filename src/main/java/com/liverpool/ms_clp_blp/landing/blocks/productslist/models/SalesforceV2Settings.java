package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.landing.models.BlockAttributes;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

/**
 * Per-block Salesforce Personalization (V2) settings authored in Contentstack
 * ({@code salesforce_v2_config} plus the block's {@code min_of_products}/{@code max_of_products}), and
 * the request identity Salesforce decides on.
 *
 * <p>Identity is header passthrough in parity with commons' {@code ProductsListSalesforceV2Strategy}
 * ({@code x-sf-user-id} / {@code x-sf-device-id}, configurable). Which of the two a block needs is
 * authored per block — the same shape as {@link JewelModelConfig} — so nothing here decides whether an
 * absent value blocks resolution; the adapter reads the {@code requires*} flags for that.</p>
 *
 * <p>Owns the CMS field names so the eager resolver, the lazy endpoint and the {@code ms_params}
 * projection cannot drift apart.</p>
 *
 * @param userId           the Salesforce logged-in id from the request; {@code null} when absent
 * @param deviceId         the Salesforce device id from the request; {@code null} when absent
 * @param model            the personalization point name; {@code null} when the block authors no
 *                         {@code salesforce_v2_config} or leaves the model blank, which makes it
 *                         unresolvable
 * @param requiresUserId   whether the point needs a logged-in id to decide anything
 * @param requiresDeviceId whether the point needs a device id to decide anything
 * @param requiresItemId   whether the point recommends against an anchor product, which a landing page never has
 * @param minOfProducts    fewest products worth rendering; {@code null} when absent
 * @param maxOfProducts    most products to render; {@code null} when absent
 */
public record SalesforceV2Settings(
        String userId,
        String deviceId,
        String model,
        boolean requiresUserId,
        boolean requiresDeviceId,
        boolean requiresItemId,
        Integer minOfProducts,
        Integer maxOfProducts) implements ProviderSettings {

    private static final String ATTR_CONFIG = "salesforce_v2_config";
    private static final String ATTR_MODEL = "model";
    private static final String ATTR_REQUIRES_USER_ID = "requires_user_id";
    private static final String ATTR_REQUIRES_DEVICE_ID = "requires_device_id";
    private static final String ATTR_REQUIRES_ITEM_ID = "requires_item_id";
    private static final String ATTR_MIN_OF_PRODUCTS = "min_of_products";
    private static final String ATTR_MAX_OF_PRODUCTS = "max_of_products";

    @Override
    public SourceOfData source() {
        return SourceOfData.SALESFORCE_V2;
    }

    /**
     * Reads the settings out of a block's raw CMS attributes.
     *
     * <p>{@code salesforce_v2_config} is optional in the CMS: a block without it yields a {@code null}
     * model and every flag {@code false}, which the adapter reports as {@code MISSING_MODEL_CONFIG}.</p>
     *
     * @param attributes raw block attributes from Contentstack, or its {@code ms_params}; may be
     *                   {@code null}
     * @param userId     the request's Salesforce logged-in id; may be {@code null}
     * @param deviceId   the request's Salesforce device id; may be {@code null}
     * @return the parsed settings, never {@code null}
     */
    @SuppressWarnings("unchecked")
    public static SalesforceV2Settings from(Map<String, Object> attributes, String userId, String deviceId) {
        if (attributes == null) {
            return new SalesforceV2Settings(userId, deviceId, null, false, false, false, null, null);
        }
        Map<String, Object> config = attributes.get(ATTR_CONFIG) instanceof Map<?, ?> raw
                ? (Map<String, Object>) raw
                : Map.of();
        return new SalesforceV2Settings(
                userId,
                deviceId,
                BlockAttributes.string(config, ATTR_MODEL),
                BlockAttributes.bool(config.get(ATTR_REQUIRES_USER_ID)),
                BlockAttributes.bool(config.get(ATTR_REQUIRES_DEVICE_ID)),
                BlockAttributes.bool(config.get(ATTR_REQUIRES_ITEM_ID)),
                BlockAttributes.integer(attributes, ATTR_MIN_OF_PRODUCTS),
                BlockAttributes.integer(attributes, ATTR_MAX_OF_PRODUCTS));
    }

    /**
     * Projects the same attributes {@link #from} reads into the {@code ms_params} the frontend re-posts.
     *
     * <p>Deliberately sits beside {@code from} and reuses its constants, so the two cannot drift. The
     * model and its flags stay nested inside {@code salesforce_v2_config} while the thresholds are read
     * from the block's top level, mirroring {@code from}. Identity is absent by design: it travels in
     * request headers, never in the body.</p>
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
        if (attributes.get(ATTR_CONFIG) instanceof Map<?, ?> raw) {
            Map<String, Object> config = new LinkedHashMap<>();
            Map<String, Object> source = (Map<String, Object>) raw;
            BlockAttributes.copyIfPresent(config, source, ATTR_MODEL);
            BlockAttributes.copyIfPresent(config, source, ATTR_REQUIRES_USER_ID);
            BlockAttributes.copyIfPresent(config, source, ATTR_REQUIRES_DEVICE_ID);
            BlockAttributes.copyIfPresent(config, source, ATTR_REQUIRES_ITEM_ID);
            params.put(ATTR_CONFIG, config);
        }
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MIN_OF_PRODUCTS);
        BlockAttributes.copyIfPresent(params, attributes, ATTR_MAX_OF_PRODUCTS);
        return params;
    }

    /**
     * @return {@code true} when no personalization point is configured, so Salesforce cannot be asked
     */
    public boolean hasNoModel() {
        return model == null || model.isBlank();
    }

    /**
     * The shopper identifier sent as {@code context.individualId}: the logged-in id when present, the
     * device id otherwise — the same precedence commons applies.
     *
     * @return the identifier, or {@code null} when the request carries neither
     */
    public String individualId() {
        return isBlank(userId) ? (isBlank(deviceId) ? null : deviceId) : userId;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
