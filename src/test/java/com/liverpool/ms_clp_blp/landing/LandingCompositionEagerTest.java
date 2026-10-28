package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

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
import com.liverpool.ms_clp_blp.landing.models.LandingFeatureFlags;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LoadStrategy;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for eager/lazy resolution decided by the offset, virtual-thread concurrency with
 * preserved order, and graceful degradation. Kept separate from {@link LandingCompositionServiceTest}
 * (which exercises the lazy-only default behaviour) for readability.
 */
class LandingCompositionEagerTest {

    private static final String FLAG_PL = "products-list-salesforce";
    private static final String FLAG_JEWEL = "jewel";
    private static final String ENDPOINT_PL = "/blp/blocks/products-list";
    private static final SessionContext AUTH_WEB = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");

    private static final FeatureFlagPort ALL_ENABLED = (flag, brand) -> true;

    /** A second provider of the same block type, to prove routing is by (type, source_of_data). */
    private static final BlockResolutionKey JEWEL_KEY =
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL);

    private final BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST), BlockResolution.post(ENDPOINT_PL, FLAG_PL),
            JEWEL_KEY, BlockResolution.batch(ENDPOINT_PL, FLAG_JEWEL)));

    // ── offset splits eager vs lazy ────────────────────────────────────────────────────────────────

    @Test
    void compose_blocksBelowOffset_resolvedEagerly_restLazy() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of("k", "v"));
        LandingCompositionService service = service(registry(resolver), 2);
        LandingDefinition def = def(productsList("pl-0"), productsList("pl-1"), productsList("pl-2"));

        LandingPage page = service.compose(def, AUTH_WEB, Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
        assertThat(page.blocks().get(1)).isInstanceOf(ResolvedBlock.class);
        assertThat(page.blocks().get(2)).isInstanceOf(DynamicPlaceholder.class);
        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(2);
        assertThat(lazy.loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        assertThat(lazy.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    @Test
    void compose_eagerBlock_inlinesResolvedPayloadWithEndpointMetadata() {
        Object payload = Map.of("title", "Ofertas");
        LandingCompositionService service =
                service(registry(RecordingResolver.returning(BlockType.PRODUCTS_LIST, payload)), 1);

        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), null);

        ResolvedBlock resolved = (ResolvedBlock) page.blocks().get(0);
        assertThat(resolved.payload()).isEqualTo(payload);
        assertThat(resolved.loadStrategy()).isEqualTo(LoadStrategy.EAGER);
        assertThat(resolved.resolutionPath()).isEqualTo(ENDPOINT_PL);
        assertThat(resolved.httpMethod()).isEqualTo("POST");
        assertThat(resolved.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    @Test
    void compose_offsetZero_everyDynamicBlockLazy() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 0);

        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(DynamicPlaceholder.class);
        assertThat(resolver.invoked()).isFalse();
    }

    // ── order preserved under concurrency ──────────────────────────────────────────────────────────

    @Test
    void compose_eagerBlocksResolvedConcurrently_preserveOrderDespiteStagger() {
        // First resolver is slower than the second; results must still land in declaration order.
        RecordingResolver slow = RecordingResolver.delayed(BlockType.PRODUCTS_LIST, Map.of("id", "slow"), 120);
        RecordingResolver fast = RecordingResolver.delayed(JEWEL_KEY, Map.of("id", "fast"), 5);
        LandingCompositionService service = service(registry(slow, fast), 5);
        LandingDefinition def = def(productsList("first"), jewelList("second"));

        LandingPage page = service.compose(def, AUTH_WEB, Map.of(), null);

        assertThat(page.blocks()).extracting(b -> ((ResolvedBlock) b).blockId())
                .containsExactly("first", "second");
        assertThat(((ResolvedBlock) page.blocks().get(0)).payload()).isEqualTo(Map.of("id", "slow"));
        assertThat(((ResolvedBlock) page.blocks().get(1)).payload()).isEqualTo(Map.of("id", "fast"));
    }

    // ── degradation ────────────────────────────────────────────────────────────────────────────────

    @Test
    void compose_eagerResolverFails_degradesToUnavailableLazyPlaceholder_pageStillRenders() {
        RecordingResolver failing = RecordingResolver.failing(BlockType.PRODUCTS_LIST);
        LandingCompositionService service = service(registry(failing), 5);
        LandingDefinition def = def(productsList("pl-0"), banner("static-1"));

        LandingPage page = service.compose(def, AUTH_WEB, Map.of(), null);

        assertThat(page.blocks()).hasSize(2);
        DynamicPlaceholder degraded = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(degraded.status()).isEqualTo(DynamicBlockStatus.UNAVAILABLE);
        assertThat(degraded.loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        assertThat(degraded.resolutionPath()).isEqualTo(ENDPOINT_PL);
        assertThat(degraded.blockId()).isEqualTo("pl-0");
    }

    // ── feature flag gate short-circuits before any I/O ───────────────────────────────────────────

    @Test
    void compose_flagDisabled_belowOffset_notResolvedAndMarkedDisabled() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        FeatureFlagPort onlyJewel = (flag, brand) -> FLAG_JEWEL.equals(flag);
        LandingCompositionService service =
                new LandingCompositionService(onlyJewel, catalog, registry(resolver), 5);

        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), null);

        assertThat(resolver.invoked()).isFalse();
        assertThat(((DynamicPlaceholder) page.blocks().get(0)).status()).isEqualTo(DynamicBlockStatus.DISABLED);
    }

    // ── dynamic type with no registered resolver stays lazy ───────────────────────────────────────

    @Test
    void compose_belowOffsetButNoResolverRegistered_staysLazyAvailable() {
        LandingCompositionService service = service(new BlockResolverRegistry(Map.of()), 5);

        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), null);

        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        assertThat(lazy.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    // ── per-request offset override & header propagation ──────────────────────────────────────────

    @Test
    void compose_offsetOverride_takesPrecedenceOverDefault() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 0); // default 0 → lazy

        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), 1); // override 1 → eager

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
    }

    // ── the default offset is chosen per audience ─────────────────────────────────────────────────

    @Test
    void compose_guestSession_usesGuestOffset() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 0, 2); // logged 0, guest 2

        LandingPage page = service.compose(def(productsList("pl-0"), productsList("pl-1")), GUEST_WEB,
                Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
        assertThat(page.blocks().get(1)).isInstanceOf(ResolvedBlock.class);
    }

    @Test
    void compose_loggedSession_usesLoggedOffset() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 0, 2); // logged 0, guest 2

        LandingPage page = service.compose(def(productsList("pl-0"), productsList("pl-1")), AUTH_WEB,
                Map.of(), null);

        assertThat(page.blocks()).allSatisfy(block -> {
            assertThat(block).isInstanceOf(DynamicPlaceholder.class);
            assertThat(((DynamicPlaceholder) block).loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        });
        assertThat(resolver.invoked()).isFalse();
    }

    @Test
    void compose_explicitOverride_winsOverSessionDefaults() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 0, 100); // production defaults

        // Authenticated, so the default would be 0 → lazy; the override says otherwise.
        LandingPage page = service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of(), 1);

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
    }

    @Test
    void compose_gatewayHeaders_arePassedToResolverContext() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of());
        LandingCompositionService service = service(registry(resolver), 1);

        service.compose(def(productsList("pl-0")), AUTH_WEB, Map.of("x-user-id", "user-42"), null);

        assertThat(resolver.lastContext().header("x-user-id")).isEqualTo("user-42");
        assertThat(resolver.lastContext().blockId()).isEqualTo("pl-0");
        assertThat(resolver.lastContext().resolutionPath()).isEqualTo(ENDPOINT_PL);
    }

    // ── A/B conditional_show_hide keeps a block out of the shared page ───────────────────────────

    /**
     * {@code GET /blp|clp/**} is cached at the edge per brand and audience, not per cohort (ADR-056). An
     * inlined A/B carousel would be whichever arm the first caller got, served to everyone — so the
     * block stays a placeholder and the per-request endpoint decides the cohort (ADR-060).
     */
    @Test
    void compose_blockSplitByCohortInsideOffset_isServedLazyWithoutResolving() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of("k", "v"));
        LandingCompositionService service = service(registry(resolver), 5);

        LandingPage page = service.compose(def(productsListWithRule("pl-ab", true)), GUEST_WEB, Map.of(), null);

        assertThat(page.blocks()).hasSize(1);
        DynamicPlaceholder lazy = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(lazy.loadStrategy()).isEqualTo(LoadStrategy.LAZY);
        assertThat(lazy.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(resolver.invoked()).isFalse();
    }

    @Test
    void compose_blockSplitByCohort_doesNotDeferItsNeighbours() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of("k", "v"));
        LandingCompositionService service = service(registry(resolver), 5);

        LandingPage page = service.compose(
                def(productsList("pl-0"), productsListWithRule("pl-ab", true), productsList("pl-2")),
                GUEST_WEB, Map.of(), null);

        assertThat(page.blocks()).extracting(block -> block.getClass().getSimpleName())
                .containsExactly("ResolvedBlock", "DynamicPlaceholder", "ResolvedBlock");
    }

    /** With the flag off the rule is ignored everywhere, so the block is as cacheable as any other. */
    @Test
    void compose_blockWithRuleAndFlagOff_isResolvedEagerly() {
        FeatureFlagPort abOff = (flag, brand) -> !LandingFeatureFlags.CONDITIONAL_SHOW_HIDE.equals(flag);
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of("k", "v"));
        LandingCompositionService service = new LandingCompositionService(abOff, catalog, registry(resolver), 5);

        LandingPage page = service.compose(def(productsListWithRule("pl-ab", true)), GUEST_WEB, Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
    }

    @Test
    void compose_blockWithInactiveRule_isResolvedEagerly() {
        RecordingResolver resolver = RecordingResolver.returning(BlockType.PRODUCTS_LIST, Map.of("k", "v"));
        LandingCompositionService service = service(registry(resolver), 5);

        LandingPage page = service.compose(def(productsListWithRule("pl-ab", false)), GUEST_WEB, Map.of(), null);

        assertThat(page.blocks().get(0)).isInstanceOf(ResolvedBlock.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** Same offset for both audiences — for the tests where the audience is not the subject. */
    private LandingCompositionService service(BlockResolverRegistry registry, int offset) {
        return new LandingCompositionService(ALL_ENABLED, catalog, registry, offset);
    }

    private LandingCompositionService service(BlockResolverRegistry registry, int loggedOffset,
                                           int guestOffset) {
        return new LandingCompositionService(ALL_ENABLED, catalog, registry, BlockParamsRegistry.empty(),
                loggedOffset, guestOffset);
    }

    private static BlockResolverRegistry registry(BlockResolver... resolvers) {
        return new BlockResolverRegistry(
                java.util.Arrays.stream(resolvers)
                        .flatMap(r -> r.resolutionKeys().stream().map(k -> java.util.Map.entry(k, r)))
                        .collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey,
                                java.util.Map.Entry::getValue)));
    }

    private static LandingDefinition def(BlockDefinition... blocks) {
        return LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(blocks));
    }

    private static BlockDefinition productsList(String uid) {
        return new BlockDefinition(uid, "products_list", null, true, true, Map.of());
    }

    private static BlockDefinition productsListWithRule(String uid, boolean active) {
        return new BlockDefinition(uid, "products_list", null, true, true, Map.of(
                "conditional_show_hide",
                Map.of("active", active, "action", "show", "header_name", "x-ab-carousel")));
    }

    /** A carousel served by the Jewel provider rather than the legacy Salesforce routing. */
    private static BlockDefinition jewelList(String uid) {
        return new BlockDefinition(uid, "products_list", null, true, true, Map.of("source_of_data", "jewel"));
    }

    private static BlockDefinition banner(String uid) {
        return new BlockDefinition(uid, "container", null, true, true, Map.of());
    }

    /**
     * Deterministic in-memory {@link BlockResolver} that records invocation and captured context, and
     * can return a payload, sleep before returning, or fail — enough to drive every eager path.
     */
    private static final class RecordingResolver implements BlockResolver {

        private final BlockResolutionKey key;
        private final Object payload;
        private final long delayMillis;
        private final boolean fail;
        private final AtomicBoolean invoked = new AtomicBoolean(false);
        private final AtomicReference<BlockResolutionContext> lastContext = new AtomicReference<>();

        private RecordingResolver(BlockResolutionKey key, Object payload, long delayMillis, boolean fail) {
            this.key = key;
            this.payload = payload;
            this.delayMillis = delayMillis;
            this.fail = fail;
        }

        static RecordingResolver returning(BlockType type, Object payload) {
            return new RecordingResolver(BlockResolutionKey.of(type), payload, 0, false);
        }

        static RecordingResolver delayed(BlockType type, Object payload, long delayMillis) {
            return delayed(BlockResolutionKey.of(type), payload, delayMillis);
        }

        static RecordingResolver delayed(BlockResolutionKey key, Object payload, long delayMillis) {
            return new RecordingResolver(key, payload, delayMillis, false);
        }

        static RecordingResolver failing(BlockType type) {
            return new RecordingResolver(BlockResolutionKey.of(type), null, 0, true);
        }

        @Override
        public Set<BlockResolutionKey> resolutionKeys() {
            return Set.of(key);
        }

        @Override
        public ResolvedContent resolve(BlockResolutionContext context) {
            invoked.set(true);
            lastContext.set(context);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) {
                throw new IllegalStateException("backing service down");
            }
            return new ResolvedContent(payload);
        }

        boolean invoked() {
            return invoked.get();
        }

        BlockResolutionContext lastContext() {
            return lastContext.get();
        }
    }
}
