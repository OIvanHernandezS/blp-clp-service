package com.liverpool.ms_clp_blp.clients.jewel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ms-clp-blp-local Jewel block behaviour settings — not covered by the reused
 * {@code com.liverpool.config.properties.ApiJewelConfig} (base URL, per-brand integration ids,
 * connect/read timeouts; see {@link JewelClientConfig}). All values are externalised — no header
 * name or threshold is ever hardcoded in application logic (Rule 6).
 *
 * <p>Every request carries its own block's CMS content (ADR-024), and a block's model, and whether it
 * requires a user/device id, are authored per block in {@code jewel_model_config} — there is no
 * service-level model or identity-requirement fallback to maintain here (ADR-029). {@code minItems}/
 * {@code maxItems} remain: {@code min_of_products}/{@code max_of_products} are optional CMS fields
 * separate from {@code jewel_model_config}, so these are the sane default when a block's author leaves
 * them blank, not a duplicate of something the CMS always supplies.</p>
 *
 * @param userIdHeader     request header carrying the Jewel tracking user id (default {@code x-jml-user-id})
 * @param deviceIdHeader   request header carrying the Jewel tracking device id (default {@code x-jml-device-id})
 * @param minItems         minimum number of items requested from Jewel when the block omits {@code min_of_products}
 * @param maxItems         maximum number of placements requested from Jewel when the block omits {@code max_of_products}
 * @param includeItemVariants whether Jewel's per-product {@code item_variants} are published in the
 *                            response; digital_bff gates the same field behind the
 *                            {@code jewel_carousel_item_variants} flag, which is <strong>on</strong>
 *                            by default and opted out with
 *                            {@code ENABLE_JEWEL_CAROUSEL_ITEM_VARIANTS=false}
 */
@ConfigurationProperties(prefix = "jewel")
public record JewelProperties(
        String userIdHeader,
        String deviceIdHeader,
        int minItems,
        int maxItems,
        boolean includeItemVariants) {
}
