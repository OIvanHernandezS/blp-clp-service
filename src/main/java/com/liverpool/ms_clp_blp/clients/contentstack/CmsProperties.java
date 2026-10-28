package com.liverpool.ms_clp_blp.clients.contentstack;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which Contentstack content types the landing endpoints read (Rule 2/6).
 *
 * <p>Content addressing only: Contentstack's transport lives in {@code ContentStackDirectProperties}
 * and its credentials in commons' {@code ContentStackProperties}.</p>
 *
 * @param defaultBrand    brand identifier used when a request supplies none
 * @param blpContentType  Contentstack content-type uid for brand landing pages (default {@code page_blp})
 * @param clpContentType  Contentstack content-type uid for category landing pages (default {@code page_clp})
 * @param redirectContentType Contentstack content-type uid for category redirects (default
 *                            {@code category_redirect})
 */
@ConfigurationProperties(prefix = "cms")
public record CmsProperties(
        String defaultBrand,
        String blpContentType,
        String clpContentType,
        String redirectContentType) {
}
