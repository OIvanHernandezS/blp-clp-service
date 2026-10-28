package com.liverpool.ms_clp_blp.landing;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.liverpool.ms_clp_blp.landing.models.BlockParamsRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockParamsBuilder;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;

/**
 * Wires pure domain objects as Spring beans. The domain layer has no Spring annotations (Rule 1), so
 * this configuration class is the single point where domain instances enter the application context.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Build one {@link BlockResolutionCatalog} per {@link PageType} from externalised configuration,
 *       mapping each dynamic block to its resolution endpoint and feature-flag id.</li>
 *   <li>Instantiate one {@link LandingCompositionService} per page type, collected in
 *       {@link LandingCompositions}.</li>
 * </ul>
 * Adding a new dynamic block type only requires extending the catalog here — no other infrastructure
 * change is needed (Open/Closed, Rule 19.5).</p>
 */
@Configuration
public class LandingConfig {

    /** Suffix of the per-page-type key holding the products-list endpoint, e.g. {@code blp-products-list-endpoint}. */
    static final String BLOCK_KEY_PRODUCTS_LIST_ENDPOINT_SUFFIX = "-products-list-endpoint";

    /** Default products-list endpoint, after the page type's route segment. */
    static final String DEFAULT_PRODUCTS_LIST_PATH = "/blocks/products-list";

    /**
     * Builds the catalog of dynamic-block resolution metadata for one page type.
     *
     * <p>{@code products_list} is served by different backends, told apart only by
     * {@code source_of_data}. They share one endpoint and differ only by feature flag, so each can be
     * switched off independently (ADR-029); the flag of each source comes from
     * {@link LandingFeatureFlags#productsListFlagId} so the catalog and the endpoint's own gate cannot
     * drift apart. {@code groupby} is aliased to the Vertex provider, exactly as digital_bff migrated
     * it on page-blp/page-clp ({@code vertexMigratedContentTypes}). Entries that predate
     * {@code source_of_data} keep their historical Salesforce routing.</p>
     *
     * @param properties landing configuration containing the endpoint paths
     * @param pageType   the page type whose blocks advertise this catalog's endpoints
     * @return an immutable catalog covering every modelled dynamic block
     */
    static BlockResolutionCatalog catalogFor(LandingProperties properties, PageType pageType) {
        String endpoint = productsListEndpoint(properties, pageType);
        Map<BlockResolutionKey, BlockResolution> entries = new HashMap<>();
        for (SourceOfData source : List.of(SourceOfData.JEWEL, SourceOfData.SALESFORCE,
                SourceOfData.SALESFORCE_V2, SourceOfData.VERTEX, SourceOfData.GROUPBY, SourceOfData.NONE)) {
            // batch(): the endpoint takes its blocks in the request body, so the advertised url is the
            // bare path — appending a block id would advertise a URL that is not mapped (ADR-029).
            entries.put(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, source),
                    BlockResolution.batch(endpoint, LandingFeatureFlags.productsListFlagId(source)));
        }
        return new BlockResolutionCatalog(entries);
    }

    /**
     * @return the products-list endpoint a page type's lazy carousels advertise —
     *         {@code landing.blocks.<type>-products-list-endpoint}, defaulting to
     *         {@code /<type>/blocks/products-list}
     */
    static String productsListEndpoint(LandingProperties properties, PageType pageType) {
        return properties.blocks().getOrDefault(
                pageType.routeSegment() + BLOCK_KEY_PRODUCTS_LIST_ENDPOINT_SUFFIX,
                "/" + pageType.routeSegment() + DEFAULT_PRODUCTS_LIST_PATH);
    }

    /**
     * Assembles the {@link BlockParamsRegistry} from every {@link BlockParamsBuilder} bean contributed by
     * the block sub-slices, keyed by the routing keys each strategy declares.
     *
     * <p>Mirrors {@link #blockResolverRegistry} exactly, including the duplicate-key guard, so the
     * parameters a block advertises and the service that resolves it are looked up the same way and can
     * never disagree.</p>
     *
     * @param builders all parameter projections contributed by block sub-slices (may be empty)
     * @return an immutable registry keyed by {@link BlockResolutionKey}
     */
    @Bean
    public BlockParamsRegistry blockParamsRegistry(List<BlockParamsBuilder> builders) {
        Map<BlockResolutionKey, BlockParamsBuilder> byKey = new HashMap<>();
        for (BlockParamsBuilder builder : builders) {
            for (BlockResolutionKey key : builder.paramKeys()) {
                BlockParamsBuilder clash = byKey.put(key, builder);
                if (clash != null) {
                    // Two slices claiming one key would make the advertised params depend on bean order.
                    throw new IllegalStateException("Duplicate BlockParamsBuilder for %s: %s and %s"
                            .formatted(key, clash.getClass().getName(), builder.getClass().getName()));
                }
            }
        }
        return new BlockParamsRegistry(byKey);
    }

    /**
     * Assembles the {@link BlockResolverRegistry} from every {@link BlockResolver} bean discovered in
     * the block sub-slices, keyed by the routing keys each strategy declares.
     *
     * <p>Spring injects the implementations by their shared {@code landing.ports} interface, so this
     * config — and the whole {@code landing} core — never references a block class (ArchUnit boundary).
     * A new dynamic block enables eager resolution simply by publishing its own resolver bean.</p>
     *
     * @param resolvers all resolver strategies contributed by block sub-slices (may be empty)
     * @return an immutable registry keyed by {@link BlockResolutionKey}
     */
    @Bean
    public BlockResolverRegistry blockResolverRegistry(List<BlockResolver> resolvers) {
        Map<BlockResolutionKey, BlockResolver> byKey = new HashMap<>();
        for (BlockResolver resolver : resolvers) {
            for (BlockResolutionKey key : resolver.resolutionKeys()) {
                BlockResolver clash = byKey.put(key, resolver);
                if (clash != null) {
                    // Two slices claiming one key would make eager routing depend on bean order.
                    throw new IllegalStateException("Duplicate BlockResolver for %s: %s and %s"
                            .formatted(key, clash.getClass().getName(), resolver.getClass().getName()));
                }
            }
        }
        return new BlockResolverRegistry(byKey);
    }

    /**
     * Instantiates the pure {@link LandingCompositionService} once per page type — POJOs with no Spring
     * lifecycle annotations, receiving their collaborators via constructor injection (Rule 4).
     *
     * @param featureFlagPort  runtime flag evaluation (satisfied by {@code FeatureFlagAdapter})
     * @param resolverRegistry eager resolver strategies per dynamic block type
     * @param paramsRegistry   {@code ms_params} projections per dynamic block type
     * @param properties       supplies the endpoints and the default eager offset for each audience
     * @return the composition services ready to be injected into the use case
     */
    @Bean
    public LandingCompositions landingCompositions(FeatureFlagPort featureFlagPort,
                                                   BlockResolverRegistry resolverRegistry,
                                                   BlockParamsRegistry paramsRegistry,
                                                   LandingProperties properties) {
        Map<PageType, LandingCompositionService> byType = new EnumMap<>(PageType.class);
        for (PageType pageType : PageType.values()) {
            byType.put(pageType, new LandingCompositionService(featureFlagPort,
                    catalogFor(properties, pageType), resolverRegistry, paramsRegistry,
                    properties.loggedEagerOffset(), properties.guestEagerOffset()));
        }
        return new LandingCompositions(byType);
    }
}
