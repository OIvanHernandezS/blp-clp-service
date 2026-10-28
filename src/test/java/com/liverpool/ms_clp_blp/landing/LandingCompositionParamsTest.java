package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.ProductsListParamsBuilder;
import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockParamsRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.landing.ports.BlockParamsBuilder;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Composition-level behaviour of the {@code ms_params} a dynamic block advertises.
 *
 * <p>The invariant under test: <strong>{@code ms_params} is present on every dynamic block, in every
 * state</strong>. A frontend that has already loaded the page must be able to call the advertised
 * {@code url} without a second {@code GET /blp|clp/**}, and the three states where that matters most — the
 * flag turned off, the eager resolution failed, the block was left lazy — are precisely the ones where
 * a naive implementation drops it.</p>
 *
 * <p>Uses the real {@link ProductsListParamsBuilder} rather than a stub, so a change to the projection
 * that breaks the wiring surfaces here too.</p>
 */
class LandingCompositionParamsTest {

    private static final String FLAG_PL = "products-list-salesforce";
    private static final String ENDPOINT_PL = "/blp/blocks/products-list";
    private static final SessionContext AUTH_WEB = new SessionContext(true, "LP", "WEB", "es-mx");

    private static final FeatureFlagPort ALL_ENABLED = (flag, brand) -> true;

    private static final Map<String, Object> JEWEL_ATTRS = Map.of(
            "source_of_data", "jewel",
            "min_of_products", "6",
            "max_of_products", "20",
            "jewel_model_config", Map.of(
                    "jewel_model", "global-tps-sales",
                    "jewel_requires_user_id", false,
                    "jewel_requires_device_id", false),
            "events", List.of(Map.of("name", "select_item")),
            "spacing", Map.of("padding_top", "none"));

    private static final Map<String, Object> EXPECTED_JEWEL_PARAMS = Map.of(
            "uid", "pl-0",
            "source_of_data", "jewel",
            "min_of_products", "6",
            "max_of_products", "20",
            "jewel_model_config", Map.of(
                    "jewel_model", "global-tps-sales",
                    "jewel_requires_user_id", false,
                    "jewel_requires_device_id", false));

    private final BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL),
            BlockResolution.batch(ENDPOINT_PL, FLAG_PL),
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
            BlockResolution.batch(ENDPOINT_PL, "vertex")));

    private final BlockParamsRegistry paramsRegistry = registryOf(new ProductsListParamsBuilder());

    // ── present in every dynamic state ───────────────────────────────────────────────────────────

    @Test
    void lazyPlaceholder_advertisesTheParametersItsEndpointReads() {
        LandingCompositionService service = service(new BlockResolverRegistry(Map.of()), 0);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(lazy.msParams()).isEqualTo(EXPECTED_JEWEL_PARAMS);
    }

    /**
     * The flag can be flipped back on while a client still holds the page it fetched. Dropping
     * {@code ms_params} here would leave that client with a {@code url} it cannot call.
     */
    @Test
    void disabledPlaceholder_stillAdvertisesItsParameters() {
        FeatureFlagPort allOff = (flag, brand) -> false;
        LandingCompositionService service = new LandingCompositionService(
                allOff, catalog, new BlockResolverRegistry(Map.of()), paramsRegistry, 5);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder disabled = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(disabled.status()).isEqualTo(DynamicBlockStatus.DISABLED);
        assertThat(disabled.msParams()).isEqualTo(EXPECTED_JEWEL_PARAMS);
    }

    /**
     * The single most important case: {@code UNAVAILABLE} means "eager resolution failed, retry via the
     * url", so the parameters that retry needs must survive the degradation.
     */
    @Test
    void unavailablePlaceholder_afterEagerFailure_stillAdvertisesItsParameters() {
        LandingCompositionService service = service(registryOf(new FailingResolver()), 5);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder degraded = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(degraded.status()).isEqualTo(DynamicBlockStatus.UNAVAILABLE);
        assertThat(degraded.msParams()).isEqualTo(EXPECTED_JEWEL_PARAMS);
    }

    /** Already resolved, but the endpoint stays advertised for refresh — so its parameters do too. */
    @Test
    void eagerlyResolvedBlock_alsoAdvertisesItsParameters() {
        LandingCompositionService service = service(registryOf(new StubResolver(Map.of("products", List.of()))), 5);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB, Map.of(), null);

        ResolvedBlock resolved = (ResolvedBlock) page.blocks().get(0);
        assertThat(resolved.msParams()).isEqualTo(EXPECTED_JEWEL_PARAMS);
    }

    // ── blocks with nothing to advertise ─────────────────────────────────────────────────────────

    /**
     * A block whose endpoint reads nothing from the body publishes no builder. Empty rather than null is
     * the contract: the frontend can post it unconditionally.
     */
    @Test
    void blockWithNoParamsBuilder_advertisesAnEmptyObject() {
        LandingCompositionService service = new LandingCompositionService(ALL_ENABLED, catalog,
                new BlockResolverRegistry(Map.of()), BlockParamsRegistry.empty(), 0);

        LandingPage page = service.compose(def(vertexBlock("vx-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.msParams()).isNotNull().isEmpty();
    }

    @Test
    void staticBlocks_carryNoParametersAtAll() {
        LandingCompositionService service = service(new BlockResolverRegistry(Map.of()), 0);

        LandingPage page = service.compose(def(banner("b-0")), AUTH_WEB, Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
    }

    /**
     * The projection reads the block's CMS settings and nothing else. Identity is resolved server-side
     * from the validated session (ADR-033) and from Jewel's tracking headers — leaking either into a
     * field the client re-posts would hand the client control of it.
     */
    @Test
    void parametersNeverCarrySessionOrHeaderIdentity() {
        LandingCompositionService service = service(new BlockResolverRegistry(Map.of()), 0);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB,
                Map.of("x-user-id", "user-42", "x-jml-user-id", "jml-7"), null);

        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.msParams().values()).doesNotContain("user-42", "jml-7");
        assertThat(lazy.msParams()).doesNotContainKeys("x-user-id", "x-jml-user-id", "userId", "profileId");
    }

    /** The advertised parameters are a strict subset of the block's own content — never an addition. */
    @Test
    void parametersAreAProjectionOfTheBlocksOwnContent() {
        LandingCompositionService service = service(new BlockResolverRegistry(Map.of()), 0);

        LandingPage page = service.compose(def(jewelBlock("pl-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.msParams()).hasSizeLessThan(lazy.content().size());
        lazy.msParams().forEach((key, value) -> {
            if (!"uid".equals(key)) {
                assertThat(lazy.content()).containsKey(key);
            }
        });
        assertThat(lazy.msParams()).doesNotContainKeys("events", "spacing");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private LandingCompositionService service(BlockResolverRegistry resolvers, int offset) {
        return new LandingCompositionService(ALL_ENABLED, catalog, resolvers, paramsRegistry, offset);
    }

    private static BlockParamsRegistry registryOf(BlockParamsBuilder builder) {
        return new BlockParamsRegistry(builder.paramKeys().stream()
                .collect(java.util.stream.Collectors.toMap(k -> k, k -> builder)));
    }

    private static BlockResolverRegistry registryOf(BlockResolver resolver) {
        return new BlockResolverRegistry(resolver.resolutionKeys().stream()
                .collect(java.util.stream.Collectors.toMap(k -> k, k -> resolver)));
    }

    private static LandingDefinition def(BlockDefinition... blocks) {
        return LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(blocks));
    }

    private static BlockDefinition jewelBlock(String uid) {
        return new BlockDefinition(uid, "products_list", null, true, true, JEWEL_ATTRS);
    }

    private static BlockDefinition vertexBlock(String uid) {
        return new BlockDefinition(uid, "products_list", null, true, true, Map.of("source_of_data", "vertex"));
    }

    private static BlockDefinition banner(String uid) {
        return new BlockDefinition(uid, "banner", null, true, true, Map.of());
    }

    private static final class StubResolver implements BlockResolver {

        private final Object payload;

        private StubResolver(Object payload) {
            this.payload = payload;
        }

        @Override
        public Set<BlockResolutionKey> resolutionKeys() {
            return Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL));
        }

        @Override
        public ResolvedContent resolve(BlockResolutionContext context) {
            return new ResolvedContent(payload);
        }
    }

    private static final class FailingResolver implements BlockResolver {

        @Override
        public Set<BlockResolutionKey> resolutionKeys() {
            return Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL));
        }

        @Override
        public ResolvedContent resolve(BlockResolutionContext context) {
            throw new IllegalStateException("backing service down");
        }
    }
}
