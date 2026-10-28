package com.liverpool.ms_clp_blp.clients.store;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for store-service (Rule 19 reference: digital_bff
 * {@code SHARED_STORE_SERVICE_URL}, {@code store-service.provider.ts}).
 *
 * @param baseUrl store-service base URL, including its {@code /v1} context path
 * @param timeout      HTTP read timeout
 * @param cacheTtl     how long a store is reused before store-service is asked again (digital_bff
 *                     cached it for a day)
 * @param cacheMaxSize ceiling on cached stores
 */
@ConfigurationProperties(prefix = "store-service")
public record StoreProperties(String baseUrl, Duration timeout, Duration cacheTtl, long cacheMaxSize) {
}
