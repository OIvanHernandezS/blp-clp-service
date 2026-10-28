package com.liverpool.ms_clp_blp.landing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.liverpool.ms_clp_blp.landing.models.BlockParamsRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.web.ServiceCredential;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the consistency of the dynamic-block contract that {@code GET /blp|clp/**} advertises.
 *
 * <p>Every placeholder tells the frontend a {@code resolutionPath} and an {@code httpMethod}. Nothing
 * previously checked that the advertised endpoint exists or accepts that method — the service shipped
 * advertising {@code POST} while its block controllers were {@code @GetMapping}, so following the
 * contract produced a 500. These are wiring invariants, so they belong in the build rather than in a
 * curl. Every page type composes against its own catalog (its carousels advertise its own
 * {@code /<type>/blocks/products-list}), so each invariant is asserted for both.</p>
 */
@ServiceCredential
@SpringBootTest(properties = "management.server.port=0")
class BlockResolutionContractTest {

    @Autowired
    private LandingProperties landingProperties;

    @Autowired
    private BlockResolverRegistry registry;

    @Autowired
    private BlockParamsRegistry paramsRegistry;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;


    @Test
    void everyAdvertisedResolutionEndpointIsMappedForItsAdvertisedMethod() {
        for (PageType pageType : PageType.values()) {
            BlockResolutionCatalog catalog = catalogFor(pageType);
            assertThat(catalog.resolutions()).isNotEmpty();
            assertThat(catalog.resolutions().values())
                    .describedAs("%s carousels advertise the %s endpoint", pageType, pageType)
                    .allMatch(resolution -> resolution.resolutionPath()
                            .startsWith("/" + pageType.routeSegment() + "/"));
            assertMapped(catalog);
        }
    }

    private void assertMapped(BlockResolutionCatalog catalog) {
        for (Map.Entry<BlockResolutionKey, BlockResolution> entry : catalog.resolutions().entrySet()) {
            BlockResolution resolution = entry.getValue();
            // Exactly the pattern the block advertises: a per-block endpoint is mapped on
            // {path}/{blockId}, the batch products_list endpoint on the bare path (ADR-029). Asserting the
            // precise shape is the point — advertising a url that is not mapped is the bug this catches.
            String pattern = resolution.blockIdInPath()
                    ? resolution.resolutionPath() + "/{blockId}"
                    : resolution.resolutionPath();

            assertThat(mappedMethodsFor(pattern))
                    .describedAs("%s advertises %s %s", entry.getKey(), resolution.httpMethod(), pattern)
                    .contains(resolution.httpMethod());
        }
    }

    /**
     * The {@code url} a block advertises must be the mapped pattern with the id substituted — the two are
     * derived from the same {@link BlockResolution}, and this pins them together so a future endpoint
     * cannot advertise an address it does not serve.
     */
    @Test
    void everyAdvertisedUrlMatchesTheShapeOfItsMappedPattern() {
        for (Map.Entry<BlockResolutionKey, BlockResolution> entry : catalogFor(PageType.BLP).resolutions().entrySet()) {
            BlockResolution resolution = entry.getValue();
            String url = resolution.urlFor("blt-example");

            assertThat(url)
                    .describedAs("%s advertises url %s", entry.getKey(), url)
                    .isEqualTo(resolution.blockIdInPath()
                            ? resolution.resolutionPath() + "/blt-example"
                            : resolution.resolutionPath());
        }
    }

    /**
     * A resolver whose key is absent from the catalog can never run: {@code LandingCompositionService}
     * only reaches the registry for blocks the catalog already classified as dynamic.
     */
    @Test
    void everyResolverKeyExistsInTheCatalog() {
        assertThat(registry.resolvers()).isNotEmpty();
        assertThat(catalogFor(PageType.CLP).resolutions().keySet()).containsAll(registry.resolvers().keySet());
    }

    /**
     * A builder whose key is absent from the catalog can never run, for the same reason a resolver
     * cannot: the composition service only reaches the registry for blocks the catalog already
     * classified as dynamic.
     */
    @Test
    void everyParamsBuilderKeyExistsInTheCatalog() {
        assertThat(paramsRegistry.builders()).isNotEmpty();
        assertThat(catalogFor(PageType.BLP).resolutions().keySet()).containsAll(paramsRegistry.builders().keySet());
    }

    /**
     * Every dynamic block must have a <em>decided</em> {@code ms_params} contract — either the keys its
     * endpoint reads, or an explicit empty set for one that resolves from the path and ignores the body.
     *
     * <p>Without this, adding a catalog key and forgetting its builder would ship a body-driven endpoint
     * advertising an empty {@code ms_params}: the frontend would post nothing, and the block would
     * resolve against service defaults — the wrong campaign, or nothing at all (ADR-022). The expectation
     * map is asserted to cover the catalog exactly, so a new key fails the build here rather than in
     * production.</p>
     */
    @Test
    void everyCatalogKeyHasADecidedParamsContract() {
        Map<BlockResolutionKey, Set<String>> expected = Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL),
                Set.of("uid", "source_of_data", "audience_filter", "jewel_model_config",
                        "min_of_products", "max_of_products"),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
                Set.of("uid", "source_of_data", "audience_filter", "salesforce_carousel",
                        "salesforce_category_id", "min_of_products", "max_of_products"),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2),
                Set.of("uid", "source_of_data", "audience_filter", "salesforce_v2_config",
                        "min_of_products", "max_of_products"),
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                Set.of("uid", "source_of_data", "audience_filter", "products_data",
                        "min_of_products", "max_of_products"),
                // groupby is aliased to the Vertex provider (ADR-055) and projects identically.
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY),
                Set.of("uid", "source_of_data", "audience_filter", "products_data",
                        "min_of_products", "max_of_products"),
                // Legacy entries carry no source_of_data, so it is absent from the projection too.
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE),
                Set.of("uid", "audience_filter", "salesforce_carousel",
                        "salesforce_category_id", "min_of_products", "max_of_products"));

        for (PageType pageType : PageType.values()) {
            assertThat(expected.keySet())
                    .describedAs("every %s catalog key needs a decided ms_params contract", pageType)
                    .isEqualTo(catalogFor(pageType).resolutions().keySet());
        }

        for (Map.Entry<BlockResolutionKey, Set<String>> entry : expected.entrySet()) {
            BlockResolutionKey key = entry.getKey();
            assertThat(paramsRegistry.paramsFor(key, "blt-example", blockFor(key)).keySet())
                    .describedAs("ms_params advertised for %s", key)
                    .isEqualTo(entry.getValue());
        }
    }

    /**
     * A block carrying every setting any provider could want, tagged with the source that routes it to
     * the given key — the composition service derives the key from this same {@code source_of_data}, so
     * the two always agree in production. Over-populating it is deliberate: it proves each provider's
     * projection takes only its own keys rather than everything present.
     */
    private static Map<String, Object> blockFor(BlockResolutionKey key) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (key.source() != SourceOfData.NONE) {
            block.put("source_of_data", key.source().name().toLowerCase(java.util.Locale.ROOT));
        }
        block.put("audience_filter", "guest");
        block.put("salesforce_carousel", "CMSOfertasIncreibles");
        block.put("salesforce_category_id", "cat-4020");
        block.put("min_of_products", "6");
        block.put("max_of_products", "20");
        block.put("jewel_model_config", Map.of(
                "jewel_model", "global-tps-sales",
                "jewel_requires_user_id", false,
                "jewel_requires_device_id", false));
        block.put("products_data", Map.of("category", "cat-123", "brandId", "LP"));
        block.put("banners", List.of(Map.of("uid", "blt-banner")));
        block.put("salesforce_v2_config", Map.of(
                "model", "home-top-sellers",
                "requires_user_id", false,
                "requires_device_id", true,
                "requires_item_id", false));
        block.put("events", List.of(Map.of("name", "select_item")));
        block.put("spacing", Map.of("padding_top", "none"));
        block.put("shortcut_items", List.of(Map.of("primary", Map.of("primary_id", "wishlist"))));
        block.put("hotspots_manager", Map.of("desktop_image_group",
                List.of(Map.of("id", 1, "x", 10.5, "y", 20.5, "productId", "1033217371"))));
        return block;
    }

    private BlockResolutionCatalog catalogFor(PageType pageType) {
        return LandingConfig.catalogFor(landingProperties, pageType);
    }

    private Set<String> mappedMethodsFor(String pattern) {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> patternsOf(e.getKey()).contains(pattern))
                .flatMap(e -> methodsOf(e.getKey(), e.getValue()).stream())
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<String> patternsOf(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? List.of()
                : info.getPathPatternsCondition().getPatternValues().stream().toList();
    }

    private Set<String> methodsOf(RequestMappingInfo info, HandlerMethod handler) {
        Set<String> declared = info.getMethodsCondition().getMethods().stream()
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        // A mapping with no explicit method condition answers every method.
        return declared.isEmpty() ? Set.of("GET", "POST") : declared;
    }
}
