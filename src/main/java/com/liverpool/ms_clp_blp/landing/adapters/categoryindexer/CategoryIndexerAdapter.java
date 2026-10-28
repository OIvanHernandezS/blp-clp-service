package com.liverpool.ms_clp_blp.landing.adapters.categoryindexer;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.liverpool.ms_clp_blp.clients.categoryindexer.CategoryIndexerClient;
import com.liverpool.ms_clp_blp.clients.categoryindexer.CategoryIndexerProperties;
import com.liverpool.ms_clp_blp.clients.categoryindexer.dto.CategoryTreeResponse;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.ports.CategoryPort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Outbound adapter reading a landing page's category from the category-tree indexer (Rule 19
 * reference: digital_bff {@code CategoryIndexerProvider.getCategoryById}).
 *
 * <p>Answers are cached in-process for {@code category-indexer.cache-ttl} (a day by default, as
 * digital_bff cached them), keyed by brand and lower-cased id. Only successes are cached: a failed
 * lookup is retried on the next request instead of hiding the category for a day. The call runs
 * behind its own circuit breaker, so an indexer outage costs one fast rejection per page rather than
 * a timeout. Every failure answers {@link Optional#empty()} — the page renders without
 * {@code categoryData} — and is reported once as {@code CATEGORY_UNAVAILABLE}.</p>
 */
@Component
public class CategoryIndexerAdapter implements CategoryPort {

    private static final Logger log = LoggerFactory.getLogger(CategoryIndexerAdapter.class);

    static final String CB_CATEGORY_INDEXER = "category-indexer";

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = "categoryindexer";

    private final CategoryIndexerClient client;
    private final CircuitBreaker breaker;
    private final Cache<String, CategoryData> cache;
    private final CategoryTreeMapper mapper = new CategoryTreeMapper();

    /**
     * @param client     the indexer client
     * @param properties cache tuning
     * @param registry   shared circuit-breaker registry
     */
    public CategoryIndexerAdapter(CategoryIndexerClient client, CategoryIndexerProperties properties,
                                  CircuitBreakerRegistry registry) {
        this.client = client;
        this.breaker = registry.circuitBreaker(CB_CATEGORY_INDEXER);
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(properties.cacheTtl())
                .maximumSize(properties.cacheMaxSize())
                .build();
    }

    @Override
    public Optional<CategoryData> findCategory(String brand, String categoryId) {
        String key = brand + '|' + categoryId.toLowerCase(Locale.ROOT);
        CategoryData cached = cache.getIfPresent(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            CategoryTreeResponse tree = breaker.executeSupplier(() -> client.findCategory(brand, categoryId));
            if (tree == null || tree.id() == null) {
                return unavailable(brand, categoryId, "the indexer answered with no category");
            }
            CategoryData category = mapper.toCategory(tree);
            cache.put(key, category);
            return Optional.of(category);
        } catch (RuntimeException e) {
            return unavailable(brand, categoryId, e.getClass().getSimpleName() + ": " + Causes.rootMessage(e));
        }
    }

    private static Optional<CategoryData> unavailable(String brand, String categoryId, String detail) {
        LogEvents.at(log, LogEvents.CATEGORY_UNAVAILABLE)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.BRAND, brand)
                .addKeyValue(LogFields.CATEGORY_ID, categoryId)
                .addKeyValue(LogFields.DETAIL, detail)
                .log("Category could not be read; serving the page without categoryData");
        return Optional.empty();
    }
}
