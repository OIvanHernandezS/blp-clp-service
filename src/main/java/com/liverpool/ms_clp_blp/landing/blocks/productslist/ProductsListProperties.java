package com.liverpool.ms_clp_blp.landing.blocks.productslist;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Behaviour settings for the {@code products_list} resolution endpoint, shared by both providers.
 *
 * <p>Picked up by {@code @ConfigurationPropertiesScan} on the application class, like
 * {@code JewelProperties}. All values are externalised — no TTL is hardcoded in application logic
 * (Rule 6).</p>
 *
 * @param cacheSeconds how long a session-independent carousel response may be cached at the edge.
 *                     ms-clp-blp runs no cache of its own (ADR-006 is superseded — caching belongs to
 *                     ContentService and Akamai), so this only sets {@code Cache-Control: max-age} and
 *                     only when <em>every</em> block in the response is session-independent. Default
 *                     300s matches digital_bff's {@code JEWEL_CACHE_TTL}. Zero disables edge caching.
 * @param maxImages    the most {@code images} each product carries in the response, for every provider.
 *                     The service-wide value: a brand may override it with
 *                     {@code settings.products-list-max-images} in its MongoDB document. Zero or
 *                     negative means no limit, the same convention as {@code max_of_products}
 */
@ConfigurationProperties(prefix = "products-list")
public record ProductsListProperties(int cacheSeconds, int maxImages) {

    static final int DEFAULT_CACHE_SECONDS = 300;

    public ProductsListProperties {
        cacheSeconds = cacheSeconds <= 0 ? DEFAULT_CACHE_SECONDS : cacheSeconds;
    }
}
