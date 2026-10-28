package com.liverpool.ms_clp_blp.landing.models;

/**
 * Parameters to fetch a raw landing page definition from Contentstack. Doubles as the CMS cache key,
 * so it holds exactly what selects an entry and nothing session-derived.
 *
 * @param brand      brand identifier ({@code x-brand-id}); selects the stack and the brand taxonomy
 * @param locale     content locale
 * @param pageType   which landing content type to read
 * @param categoryId the category the page is authored for — the last segment of the storefront URL
 */
public record ContentQuery(String brand, String locale, PageType pageType, String categoryId) {
}
