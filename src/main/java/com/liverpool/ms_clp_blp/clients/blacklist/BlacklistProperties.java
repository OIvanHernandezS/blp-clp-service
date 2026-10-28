package com.liverpool.ms_clp_blp.clients.blacklist;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the product blacklist.
 *
 * <p>The endpoint is addressed by <em>site</em>, not by brand code: catalog-ingestion keys its lists by
 * platform-commons' {@code BrandSite} enum name ({@code LIVERPOOL}, {@code SUBURBIA}, …), so
 * {@link #sites} maps each {@code x-brand-id} onto one. A brand with no mapping has no blacklist.</p>
 *
 * @param baseUrl base URL of the decommission gateway hosting catalog-ingestion-service
 * @param path    path of the blacklist endpoint; the site is appended
 * @param limit   how many ids to ask for; {@code -1} asks for the whole list, as carousels need
 * @param timeout  HTTP read timeout
 * @param cacheTtl how long a brand's list is reused before it is read again (ms_pdp_search's
 *                 {@code caffeine.restricted-products.ttl} default is 10 minutes)
 * @param sites    {@code x-brand-id} → site name, e.g. {@code LP → LIVERPOOL}
 */
@ConfigurationProperties(prefix = "blacklist")
public record BlacklistProperties(String baseUrl, String path, int limit, Duration timeout,
                                  Duration cacheTtl, Map<String, String> sites) {

    public BlacklistProperties {
        sites = sites == null ? Map.of() : Map.copyOf(sites);
    }

    /**
     * @param brand the {@code x-brand-id} value, any case
     * @return the site the blacklist is keyed by, or {@code null} when the brand has none
     */
    public String siteOf(String brand) {
        if (brand == null || brand.isBlank()) {
            return null;
        }
        return sites.get(brand.trim().toUpperCase(Locale.ROOT));
    }
}
