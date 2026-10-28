package com.liverpool.ms_clp_blp.landing.adapters.blacklist;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liverpool.ms_clp_blp.clients.blacklist.BlacklistClient;
import com.liverpool.ms_clp_blp.clients.blacklist.BlacklistProperties;
import com.liverpool.ms_clp_blp.clients.blacklist.dto.BlacklistResponse;
import com.liverpool.ms_clp_blp.landing.ports.RestrictedProductsPort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Outbound adapter reading a brand's product blacklist from catalog-ingestion-service (Rule 19
 * reference: ms_pdp_search / platform-commons {@code RestrictedProductListService} on
 * {@code feat/AHDV-401}, carousel variant: the whole list, {@code limit=-1}).
 *
 * <p>The list is read once per brand per {@code blacklist.cache-ttl} (10 minutes by default, as
 * ms_pdp_search caches it) and shared by every carousel of every page. A brand with no configured
 * site has no blacklist. <strong>Fails open:</strong> a failed read answers an empty set and is not
 * cached, so carousels are served unfiltered until the next read succeeds — never emptied.</p>
 */
@Component
public class RestrictedProductsAdapter implements RestrictedProductsPort {

    private static final Logger log = LoggerFactory.getLogger(RestrictedProductsAdapter.class);

    static final String CB_BLACKLIST = "carousel-blacklist";

    private static final String UPSTREAM = "blacklist";

    private final BlacklistClient client;
    private final BlacklistProperties properties;
    private final CircuitBreaker breaker;
    private final Cache<String, Set<String>> cache;

    /**
     * @param client     the blacklist client
     * @param properties site mapping, list size and cache tuning
     * @param registry   shared circuit-breaker registry
     */
    public RestrictedProductsAdapter(BlacklistClient client, BlacklistProperties properties,
                                     CircuitBreakerRegistry registry) {
        this.client = client;
        this.properties = properties;
        this.breaker = registry.circuitBreaker(CB_BLACKLIST);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .build();
    }

    @Override
    public Set<String> restrictedProductIds(String brand) {
        String site = properties.siteOf(brand);
        if (site == null) {
            return Set.of();
        }
        Set<String> cached = cache.getIfPresent(site);
        if (cached != null) {
            return cached;
        }
        try {
            BlacklistResponse response = breaker.executeSupplier(
                    () -> client.findRestrictedProducts(site, properties.limit()));
            Set<String> ids = response == null || response.products() == null
                    ? Set.of() : Set.copyOf(response.products().stream().filter(id -> id != null).toList());
            cache.put(site, ids);
            return ids;
        } catch (RuntimeException e) {
            LogEvents.at(log, LogEvents.RESTRICTED_PRODUCTS_UNAVAILABLE)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BRAND, brand)
                    .addKeyValue(LogFields.DETAIL, e.getClass().getSimpleName() + ": " + Causes.rootMessage(e))
                    .log("Product blacklist could not be read; carousels are served unfiltered");
            return Set.of();
        }
    }
}
