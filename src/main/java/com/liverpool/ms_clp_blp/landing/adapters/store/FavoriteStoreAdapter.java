package com.liverpool.ms_clp_blp.landing.adapters.store;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liverpool.ms_clp_blp.clients.store.StoreClient;
import com.liverpool.ms_clp_blp.clients.store.StoreProperties;
import com.liverpool.ms_clp_blp.clients.store.dto.StoreDetailsResponse;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.ports.FavoriteStorePort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Outbound adapter resolving the {@code favoriteStore} cookie's store id against store-service
 * (Rule 19 reference: digital_bff {@code StoreServiceProvider.getStoreDetails}).
 *
 * <p>The store id travels as typed by the cookie; the answer's numeric {@code storeId} is what
 * analytics receives as {@code selected_store.code} (digital_bff published it as a number, {@code 7}).
 * Stores are cached for {@code store-service.cache-ttl} (a day by default), only on success, behind
 * their own circuit breaker. Every failure answers {@link Optional#empty()}.</p>
 */
@Component
public class FavoriteStoreAdapter implements FavoriteStorePort {

    private static final Logger log = LoggerFactory.getLogger(FavoriteStoreAdapter.class);

    static final String CB_STORE_SERVICE = "store-service";

    private static final String UPSTREAM = "store";

    private final StoreClient client;
    private final CircuitBreaker breaker;
    private final Cache<String, SelectedStore> cache;

    /**
     * @param client     the store-service client
     * @param properties cache tuning
     * @param registry   shared circuit-breaker registry
     */
    public FavoriteStoreAdapter(StoreClient client, StoreProperties properties, CircuitBreakerRegistry registry) {
        this.client = client;
        this.breaker = registry.circuitBreaker(CB_STORE_SERVICE);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxSize())
                .build();
    }

    @Override
    public Optional<SelectedStore> findStore(String brand, String storeId) {
        String key = brand + '|' + storeId;
        SelectedStore cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            StoreDetailsResponse store = breaker.executeSupplier(() -> client.findStore(brand, storeId));
            if (store == null || store.name() == null) {
                return unavailable(brand, storeId, "store-service answered with no store");
            }
            SelectedStore selected = new SelectedStore(code(store.storeId(), storeId), store.name());
            cache.put(key, selected);
            return Optional.of(selected);
        } catch (RuntimeException e) {
            return unavailable(brand, storeId, e.getClass().getSimpleName() + ": " + Causes.rootMessage(e));
        }
    }

    /**
     * The analytics code: the store-service id as a number when it is one (digital_bff's
     * {@code +storeId}), otherwise the cookie's value as typed.
     */
    private static Object code(Object storeId, String requested) {
        Object raw = storeId == null ? requested : storeId;
        if (raw instanceof Number number) {
            return number;
        }
        try {
            return Long.valueOf(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return String.valueOf(raw);
        }
    }

    private static Optional<SelectedStore> unavailable(String brand, String storeId, String detail) {
        LogEvents.at(log, LogEvents.FAVORITE_STORE_UNAVAILABLE)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(LogFields.STORE_ID, storeId)
                .addKeyValue(LogFields.DETAIL, detail)
                .log("Favourite store could not be resolved; selected_store values are left unset");
        return Optional.empty();
    }
}
