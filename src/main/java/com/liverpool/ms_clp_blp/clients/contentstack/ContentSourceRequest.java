package com.liverpool.ms_clp_blp.clients.contentstack;

/**
 * One landing-page CMS read.
 *
 * @param contentType Contentstack content-type uid, {@code page_blp} or {@code page_clp}
 * @param locale      locale to resolve the entry in
 * @param categoryId  the category the page is authored for — the last segment of the storefront URL
 * @param brandHeader the {@code x-brand-id} value
 */
public record ContentSourceRequest(
        String contentType,
        String locale,
        String categoryId,
        String brandHeader) {
}
