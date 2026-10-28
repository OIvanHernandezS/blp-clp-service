package com.liverpool.ms_clp_blp.clients.categoryindexer;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the category-tree indexer (Rule 19 reference: digital_bff
 * {@code SHARED_CATEGORY_INDEXER_URL}, {@code category-indexer.provider.ts}).
 *
 * @param baseUrl    indexer base URL, including its {@code /v1} context path
 * @param searchPath path prefix of the category lookup; the brand and the category id are appended
 * @param timeout    HTTP read timeout
 * @param cacheTtl   how long a category is reused before the indexer is asked again (digital_bff
 *                   cached it for a day)
 * @param cacheMaxSize ceiling on cached categories
 */
@ConfigurationProperties(prefix = "category-indexer")
public record CategoryIndexerProperties(String baseUrl, String searchPath, Duration timeout,
                                        Duration cacheTtl, long cacheMaxSize) {
}
