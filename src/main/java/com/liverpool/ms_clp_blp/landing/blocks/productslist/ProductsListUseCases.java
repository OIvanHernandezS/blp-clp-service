package com.liverpool.ms_clp_blp.landing.blocks.productslist;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Service;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.ProductRecommendation.ProductImage;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListPort;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingSettings;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.landing.ports.RestrictedProductsPort;
import com.liverpool.ms_clp_blp.landing.ports.RuntimeSettingsPort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;
import com.liverpool.ms_clp_blp.shared.logging.MdcPropagation;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Application use case for resolving the dynamic {@code products_list} block, whichever service backs it
 * (Rule 18 — independent per-block resolution; ADR-029 — one block, several providers).
 *
 * <p>Holds one {@link ProductsListPort} and one circuit breaker <strong>per {@code source_of_data}</strong>,
 * indexed at construction. A provider's breaker isolates that <em>service</em>, not an individual
 * carousel: every Jewel-backed carousel shares {@value #CB_JEWEL}, so a Jewel outage sheds load for all
 * of them while leaving Salesforce carousels and the rest of the landing page untouched. Each breaker
 * inherits the global 5%-threshold, no-retry policy from the shared {@link CircuitBreakerRegistry}
 * (ADR-004).</p>
 *
 * <p>Two failure signals reach the caller:</p>
 * <ul>
 *   <li>{@link ServiceUnavailableException} — circuit breaker open (load-shedding)</li>
 *   <li>{@link DynamicBlockServiceUnavailableException} — the backing service returned an error</li>
 * </ul>
 *
 * <p>A block that simply cannot be personalised for this session is <strong>not</strong> a failure: it
 * resolves to an {@link DynamicBlockStatus#EMPTY} carousel carrying the reason, and the caller drops it
 * without retrying (ADR-022, ADR-043). That decision is taken by {@link ProductsListPort#skipReason}
 * before the breaker is engaged, so it never counts as a call.</p>
 *
 * <p>Ahead of even that, the block's A/B {@code conditional_show_hide} rule is applied for every
 * provider alike, gated by the {@code conditional-show-hide} flag — a port of the commons evaluator, so
 * a carousel splits cohorts on a landing page exactly as it does on PDP and PLP (ADR-060).</p>
 *
 * <p>And ahead of everything, each provider's own kill switch. A block whose {@code source_of_data}
 * is switched off for the brand resolves to {@link DynamicBlockStatus#EMPTY} carrying
 * {@link BlockDropReason#FEATURE_FLAG_OFF}, without a provider call and without a breaker permit.
 * The page already marks such a block {@code DISABLED}, but the placeholder it publishes
 * still carries the resolution url, so without this check a caller that ignores the status — a stale
 * frontend, a cached page, a manual call — would reach the provider anyway.</p>
 */
@Service
public class ProductsListUseCases implements ProductsListService {

    private static final Logger log = LoggerFactory.getLogger(ProductsListUseCases.class);

    static final String CB_SALESFORCE = "products-list-salesforce";
    static final String CB_SALESFORCE_V2 = "products-list-salesforce-v2";
    static final String CB_JEWEL = "products-list-jewel";
    static final String CB_VERTEX = "products-list-vertex";

    private final Map<SourceOfData, ProductsListPort> ports;
    private final Map<SourceOfData, CircuitBreaker> breakers;
    private final FeatureFlagPort featureFlags;
    private final RuntimeSettingsPort runtimeSettings;
    private final RestrictedProductsPort restrictedProducts;
    private final ProductsListProperties properties;

    /**
     * @param ports           every provider adapter on the classpath; indexed by {@link ProductsListPort#source()}
     * @param cbRegistry      shared circuit-breaker registry; a named breaker is created on first use
     * @param featureFlags    per-brand runtime flags; decides whether A/B {@code conditional_show_hide} rules
     *                        are honoured
     * @param runtimeSettings per-brand numeric settings; decides how many images each product keeps
     * @param restrictedProducts the per-brand product blacklist removed from every carousel
     * @param properties      supplies the service-wide image limit a brand falls back to
     * @throws IllegalStateException when two adapters claim the same source, which would make routing
     *                               depend on bean ordering
     */
    public ProductsListUseCases(List<ProductsListPort> ports, CircuitBreakerRegistry cbRegistry,
                                FeatureFlagPort featureFlags, RuntimeSettingsPort runtimeSettings,
                                RestrictedProductsPort restrictedProducts, ProductsListProperties properties) {
        this.featureFlags = featureFlags;
        this.runtimeSettings = runtimeSettings;
        this.restrictedProducts = restrictedProducts;
        this.properties = properties;
        this.ports = index(ports);
        this.breakers = new EnumMap<>(SourceOfData.class);
        this.breakers.put(SourceOfData.SALESFORCE, cbRegistry.circuitBreaker(CB_SALESFORCE));
        this.breakers.put(SourceOfData.SALESFORCE_V2, cbRegistry.circuitBreaker(CB_SALESFORCE_V2));
        this.breakers.put(SourceOfData.JEWEL, cbRegistry.circuitBreaker(CB_JEWEL));
        this.breakers.put(SourceOfData.VERTEX, cbRegistry.circuitBreaker(CB_VERTEX));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Applies the provider's kill switch, routes on {@code source_of_data}, short-circuits blocks
     * that cannot resolve, then wraps the provider call in that provider's breaker and applies the
     * block's product thresholds.</p>
     *
     * @throws ServiceUnavailableException             when the provider's circuit breaker is open
     * @throws DynamicBlockServiceUnavailableException when the backing service returns an error, or no
     *                                                adapter owns the block's source
     */
    @Override
    public ProductsListResolution resolve(ProductsListQuery query) {
        BlockDropReason flagReason = featureFlagReason(query);
        if (flagReason != null) {
            return ProductsListResolution.empty(query.blockId(), flagReason);
        }

        ProductsListPort port = portFor(query);
        CircuitBreaker breaker = breakers.get(query.source());

        BlockDropReason cohortReason = conditionalShowHideReason(query);
        if (cohortReason != null) {
            return ProductsListResolution.empty(query.blockId(), cohortReason);
        }

        BlockDropReason skipReason = port.skipReason(query);
        if (skipReason != null) {
            // The adapter has already logged which precondition was missing (BLOCK_SKIPPED + reason);
            // carrying the same value into the resolution is what puts it on the wire too (ADR-043).
            return ProductsListResolution.empty(query.blockId(), skipReason);
        }

        try {
            return limitImages(query, filterRestricted(query,
                    applyThresholds(query, breaker.executeSupplier(() -> port.fetch(query)))));
        } catch (CallNotPermittedException e) {
            LogEvents.at(log, LogEvents.CIRCUIT_BREAKER_OPEN)
                    .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                    .addKeyValue(LogFields.SOURCE, query.source())
                    .addKeyValue(LogFields.BREAKER, breaker.getName())
                    .log("Circuit breaker is open; the products_list call was rejected before it was made");
            throw new ServiceUnavailableException(
                    "Circuit breaker '" + breaker.getName() + "' is open; products-list call rejected.", e);
        } catch (DynamicBlockServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    "Unexpected error resolving products_list from backing service: " + e.getMessage(),
                    e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>One virtual thread per block (ADR-007), so wall time is the slowest single provider call rather
     * than their sum. Mirrors {@code LandingCompositionService}'s eager fan-out, including its
     * degrade-on-failure behaviour: a block whose provider errored comes back
     * {@code UNAVAILABLE} instead of failing the whole batch.</p>
     */
    @Override
    public List<ProductsListResolution> resolveAll(List<ProductsListQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return List.of();
        }
        List<ProductsListResolution> out = new ArrayList<>(queries.size());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<ProductsListResolution>> futures = new ArrayList<>(queries.size());
            for (ProductsListQuery query : queries) {
                // Wrapped so each block's log lines keep the request's correlation ids: a virtual
                // thread starts with an empty MDC (see MdcPropagation).
                futures.add(executor.submit(MdcPropagation.wrap(() -> resolve(query))));
            }
            for (int i = 0; i < queries.size(); i++) {
                out.add(joinOrDegrade(queries.get(i), futures.get(i)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Whether every one of these blocks resolves the same way for all callers, so the response may be
     * cached at the edge. A single personalised block makes the whole response uncacheable.
     *
     * @param queries the blocks in the request
     * @return {@code true} only when all blocks are session-independent
     */
    public boolean allSessionIndependent(List<ProductsListQuery> queries) {
        if (queries == null || queries.isEmpty()) {
            return false;
        }
        return queries.stream().allMatch(query -> {
            ProductsListPort port = ports.get(query.source());
            // A carousel behind an A/B rule answers differently per cohort, so it can never be shared.
            // A block switched off by its provider's flag is deliberately NOT considered here: its
            // answer is the same for every caller, which is the only question this method asks.
            return port != null && !splitsByCohort(query) && port.sessionIndependent(query);
        });
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    private static Map<SourceOfData, ProductsListPort> index(List<ProductsListPort> ports) {
        Map<SourceOfData, ProductsListPort> bySource = new EnumMap<>(SourceOfData.class);
        for (ProductsListPort port : ports) {
            ProductsListPort clash = bySource.put(port.source(), port);
            if (clash != null) {
                throw new IllegalStateException("Duplicate ProductsListPort for %s: %s and %s"
                        .formatted(port.source(), clash.getClass().getName(), port.getClass().getName()));
            }
        }
        return bySource;
    }

    /**
     * Applies the provider's kill switch, ahead of everything else this block would cost.
     *
     * <p>Each {@code source_of_data} carries its own flag (ADR-029), so one provider can be switched
     * off without touching the others. This is checked <strong>before the circuit breaker</strong>, as
     * a kill switch should be: {@code executeSupplier} both takes a permit and records
     * the call in the sliding window, so routing an off provider through it would let a deliberate
     * kill switch move a breaker shared by every carousel of that provider — and with the breaker
     * already open the block would come back {@code UNAVAILABLE}/{@code CIRCUIT_BREAKER_OPEN}, which
     * invites the frontend to retry something nobody wants resolved.</p>
     *
     * <p>It is also checked before the A/B rule and before the adapter's own preconditions. Ahead of
     * the rule because a kill switch is "off for everyone" while a cohort rule is "off for this
     * shopper", so {@code FEATURE_FLAG_OFF} is the operative answer when both apply — and because it
     * saves reading a second flag. Ahead of {@link ProductsListPort#skipReason} because that is where
     * each adapter logs the precondition it is missing, and an off provider would file a true-looking
     * but irrelevant reason ("no device id") instead of the real one.</p>
     *
     * <p>A source no flag governs counts as enabled, so this gate can only ever stop a carousel
     * somebody explicitly turned off — see {@code LandingFeatureFlags.productsListFlagId} for why that
     * fail-open is not the same question as an undeclared flag's {@code false}.</p>
     *
     * @param query the block being resolved; its session supplies the brand the flag resolves for
     * @return {@link BlockDropReason#FEATURE_FLAG_OFF} when the provider is off for this brand,
     *         {@code null} when it is on or when no flag governs the block's source
     */
    private BlockDropReason featureFlagReason(ProductsListQuery query) {
        String flagId = LandingFeatureFlags.productsListFlagId(query.source());
        if (flagId == null || featureFlags.isEnabled(flagId, brandOf(query))) {
            return null;
        }
        LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                .addKeyValue(LogFields.SOURCE, query.source())
                .addKeyValue(LogFields.FLAG_ID, flagId)
                .addKeyValue(LogFields.REASON, BlockDropReason.FEATURE_FLAG_OFF)
                .log("The block's provider is switched off for this brand; resolved to nothing without calling it");
        return BlockDropReason.FEATURE_FLAG_OFF;
    }

    /**
     * The brand whose flags apply to this block. The only thing this slice reads from the session —
     * identity stays out of it by design (ArchUnit {@code identityFreeSlicesDoNotReadIdentity}).
     *
     * @return the caller's brand, or {@code null} when no session was resolved, which falls the flag
     *         back to its service-wide value
     */
    private static String brandOf(ProductsListQuery query) {
        return query.session() == null ? null : query.session().brand();
    }

    /**
     * Applies the block's A/B {@code conditional_show_hide} rule, ahead of every provider precondition —
     * the same position the commons strategies give it — so an excluded cohort never costs a breaker
     * permit or an upstream call.
     *
     * @return the reason the rule removes this block for the shopper, or {@code null} when it is kept
     *         (the flag is off, the rule does not apply, or the shopper's cohort matches it)
     */
    private BlockDropReason conditionalShowHideReason(ProductsListQuery query) {
        if (!splitsByCohort(query)) {
            return null;
        }
        ConditionalShowHide rule = query.conditionalShowHide();
        // No session means no markers, which leaves the cohort unknown — as a null request does in commons.
        BlockDropReason reason =
                rule.dropReason(query.session() == null ? null : query.session().cohortMarkers());
        if (reason != null) {
            LogEvents.at(log, LogEvents.BLOCK_SKIPPED)
                    .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                    .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, query.source())
                    .addKeyValue(LogFields.REASON, reason)
                    .addKeyValue(LogFields.COHORT_HEADER, rule.headerName())
                    .addKeyValue(LogFields.COHORT_COOKIE, rule.cookieName())
                    .log("The block's conditional_show_hide rule removes it for this shopper's cohort");
        }
        return reason;
    }

    /**
     * Whether this carousel's content depends on the shopper's A/B cohort: its rule applies and the
     * {@code conditional-show-hide} flag is on for the brand. The flag is read only for a block that
     * carries an applicable rule, so the common case costs no lookup.
     */
    private boolean splitsByCohort(ProductsListQuery query) {
        return query.conditionalShowHide().applies()
                && featureFlags.isEnabled(LandingFeatureFlags.CONDITIONAL_SHOW_HIDE, brandOf(query));
    }

    private ProductsListPort portFor(ProductsListQuery query) {
        ProductsListPort port = ports.get(query.source());
        if (port == null) {
            throw new DynamicBlockServiceUnavailableException(
                    query.blockId(),
                    BlockType.PRODUCTS_LIST.contentTypeUid(),
                    "No products_list adapter owns source_of_data=" + query.source(),
                    null);
        }
        return port;
    }

    /**
     * Applies the block's {@code min_of_products}/{@code max_of_products} for <em>every</em> provider.
     *
     * <p>Below the minimum the carousel resolves to nothing rather than to a short list, so the caller
     * drops the block instead of rendering a sparse row; above the maximum the list is truncated. Jewel
     * also forwards these to its API as a hint, but the API is not required to honour them — enforcing
     * here is what makes the two providers behave alike (ADR-029).</p>
     *
     * <p>A non-positive threshold means <em>unset</em>, not "allow nothing": Jewel's thresholds arrive as
     * primitive ints that default to zero when a block authors {@code max_of_products: 0}, and treating
     * that literally would silently empty a perfectly good carousel.</p>
     */
    private ProductsListResolution applyThresholds(ProductsListQuery query,
                                                   ProductsListResolution resolution) {
        Integer min = query.provider().minOfProducts();
        Integer max = query.provider().maxOfProducts();
        List<ProductRecommendation> products = resolution.products();

        if (min != null && min > 0 && products.size() < min) {
            LogEvents.at(log, LogEvents.BLOCK_DROPPED)
                    .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                    .addKeyValue(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid())
                    .addKeyValue(LogFields.SOURCE, query.source())
                    .addKeyValue(LogFields.REASON, BlockDropReason.BELOW_MIN_PRODUCTS)
                    .addKeyValue(LogFields.PRODUCT_COUNT, products.size())
                    .addKeyValue(LogFields.MIN_OF_PRODUCTS, min)
                    .log("Fewer products than the block's minimum; resolving to nothing so it is dropped");
            return resolution.emptied(BlockDropReason.BELOW_MIN_PRODUCTS);
        }
        if (max != null && max > 0 && products.size() > max) {
            return resolution.withProducts(products.subList(0, max));
        }
        return resolution;
    }

    /**
     * Removes the brand's blacklisted products from a carousel that will render, gated by the
     * {@code carousel-restricted-products} flag (Rule 19 reference: digital_bff
     * {@code filterBlacklistedCarouselProducts}; ms_pdp_search {@code isRestrictedForCarousel}).
     *
     * <p>Runs after the thresholds, as digital_bff ran it after its strategies: {@code min_of_products}
     * is not re-checked, so a carousel that met its minimum keeps rendering even if the blacklist
     * thins it below. Applied here — not in the four provider adapters, not in the page composition —
     * so the inlined carousel of the page and the lazy {@code POST …/blocks/products-list} answer filter
     * identically. The list read fails open (see {@link RestrictedProductsPort}).</p>
     */
    private ProductsListResolution filterRestricted(ProductsListQuery query, ProductsListResolution resolution) {
        if (resolution.status() != DynamicBlockStatus.AVAILABLE || resolution.isEmpty()
                || !featureFlags.isEnabled(LandingFeatureFlags.CAROUSEL_RESTRICTED_PRODUCTS, brandOf(query))) {
            return resolution;
        }
        Set<String> restricted = restrictedProducts.restrictedProductIds(brandOf(query));
        if (restricted.isEmpty()) {
            return resolution;
        }
        List<ProductRecommendation> kept = resolution.products().stream()
                .filter(product -> !restricted.contains(product.getProductId()))
                .toList();
        int removed = resolution.products().size() - kept.size();
        if (removed == 0) {
            return resolution;
        }
        LogEvents.at(log, LogEvents.RESTRICTED_PRODUCTS_REMOVED)
                .addKeyValue(LogFields.BLOCK_ID, query.blockId())
                .addKeyValue(LogFields.SOURCE, query.source())
                .addKeyValue(LogFields.PRODUCTS_REMOVED, removed)
                .log("Blacklisted products removed from the carousel");
        return resolution.withProducts(kept);
    }

    /**
     * Caps each product's {@code images} at the brand's {@code products-list-max-images}, falling back
     * to {@code products-list.max-images}. Applied here rather than in the four provider mappers so every
     * provider, and both the batch endpoint and the eager page path, obey one limit.
     *
     * <p>The setting is evaluated once per block, not per product, and only for a block that will
     * render. A non-positive value means no limit. Products are trimmed in place: each one is built
     * fresh by its provider's mapper for this call, and nothing in ms-clp-blp caches them.</p>
     */
    private ProductsListResolution limitImages(ProductsListQuery query, ProductsListResolution resolution) {
        if (resolution.status() != DynamicBlockStatus.AVAILABLE || resolution.isEmpty()) {
            return resolution;
        }
        int max = runtimeSettings.intValue(
                LandingSettings.PRODUCTS_LIST_MAX_IMAGES, brandOf(query), properties.maxImages());
        if (max <= 0) {
            return resolution;
        }
        for (ProductRecommendation product : resolution.products()) {
            List<ProductImage> images = product.getImages();
            if (images != null && images.size() > max) {
                product.setImages(new ArrayList<>(images.subList(0, max)));
            }
        }
        return resolution;
    }

    private ProductsListResolution joinOrDegrade(ProductsListQuery query,
                                                 Future<ProductsListResolution> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logDegradation(query, e).log("Block resolution was interrupted; degrading to UNAVAILABLE");
            return ProductsListResolution.unavailable(query.blockId(), BlockDropReason.UPSTREAM_FAILED);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logDegradation(query, cause).log("Block resolution failed; degrading to UNAVAILABLE");
            return ProductsListResolution.unavailable(query.blockId(), degradeReason(cause));
        }
    }

    /**
     * Which failure the frontend is being told about. Load-shedding and a genuine upstream error both
     * degrade the block, but they are different operational stories and the response says which:
     * {@link ServiceUnavailableException} is this service refusing to call, anything else is the call
     * having been made and failed.
     */
    private static BlockDropReason degradeReason(Throwable cause) {
        return cause instanceof ServiceUnavailableException
                ? BlockDropReason.CIRCUIT_BREAKER_OPEN
                : BlockDropReason.UPSTREAM_FAILED;
    }

    /**
     * Builds the degradation line with the typed cause's own fields — error code, category, and
     * which upstream answered what — so a batch row that came back UNAVAILABLE can be explained
     * without re-running the request. An untyped cause is a defect in this service rather than an
     * upstream failure, so it is logged at ERROR with its stack trace.
     */
    private LoggingEventBuilder logDegradation(ProductsListQuery query, Throwable cause) {
        boolean typed = cause instanceof ClpBlpException;

        // Merged before emitting: the exception's own context overlaps with the query's (both name the
        // block), and emitting a key twice would render it twice in the console pattern.
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(LogFields.BLOCK_ID, query.blockId());
        fields.put(LogFields.BLOCK_TYPE, BlockType.PRODUCTS_LIST.contentTypeUid());
        fields.put(LogFields.SOURCE, query.source());
        fields.put(LogFields.ERROR_CLASS, cause.getClass().getSimpleName());
        fields.put(LogFields.CAUSE_CHAIN, Causes.chain(cause));
        fields.put(LogFields.DETAIL, Causes.rootMessage(cause));
        if (cause instanceof ClpBlpException domain) {
            fields.put(LogFields.ERROR_CODE, domain.getErrorCode());
            fields.put(LogFields.CATEGORY, domain.getCategory());
            fields.put(LogFields.RETRYABLE, domain.isRetryable());
            fields.putAll(domain.context());
        }

        LoggingEventBuilder builder =
                LogEvents.at(log, LogEvents.BLOCK_DEGRADED, typed ? Level.WARN : Level.ERROR);
        fields.forEach(builder::addKeyValue);
        return typed ? builder : builder.setCause(cause);
    }
}
