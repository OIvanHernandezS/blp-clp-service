package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionCatalog;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionContext;
import com.liverpool.ms_clp_blp.landing.models.BlockResolutionKey;
import com.liverpool.ms_clp_blp.landing.models.BlockResolverRegistry;
import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routing of {@code products_list} blocks by {@code source_of_data}.
 *
 * <p>The live home template ships several {@code products_list} blocks backed by different systems,
 * told apart only by this field. Keying resolution on {@code _content_type_uid} alone sent every one
 * of them to the Salesforce adapter — including four Jewel-backed carousels — so these tests pin the
 * discriminator rather than just the happy path.</p>
 */
class LandingCompositionSourceRoutingTest {

    private static final String FLAG_JEWEL = "jewel";
    private static final String FLAG_SALESFORCE = "products-list-salesforce";
    private static final String FLAG_VERTEX = "vertex";
    private static final String FLAG_SALESFORCE_V2 = "products-list-salesforce-v2";
    // ADR-029: one endpoint for every provider of products_list; the feature flag is the per-provider
    // discriminator, so routing is still observable in the placeholder.
    private static final String ENDPOINT_PRODUCTS_LIST = "/blp/blocks/products-list";
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final FeatureFlagPort ALL_ENABLED = (flag, brand) -> true;

    private final BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_JEWEL),
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_SALESFORCE),
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.NONE),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_SALESFORCE),
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_SALESFORCE_V2),
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_VERTEX),
            // groupby is aliased to the Vertex provider (ADR-055) — same endpoint and flag as vertex.
            BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY),
            BlockResolution.post(ENDPOINT_PRODUCTS_LIST, FLAG_VERTEX)));

    // ── lazy placeholders advertise the endpoint of the block's own backing service ────────────────

    @Test
    void compose_jewelBackedProductsList_advertisesTheJewelFlag() {
        LandingPage page = lazyService().compose(def(productsList("pl-jewel", "jewel")), GUEST_WEB);

        DynamicPlaceholder placeholder = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(placeholder.resolutionPath()).isEqualTo(ENDPOINT_PRODUCTS_LIST);
        assertThat(placeholder.featureFlagId()).isEqualTo(FLAG_JEWEL);
    }

    @Test
    void compose_salesforceBackedProductsList_advertisesSalesforceEndpointAndFlag() {
        LandingPage page = lazyService().compose(def(productsList("pl-sf", "salesforce")), GUEST_WEB);

        DynamicPlaceholder placeholder = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(placeholder.resolutionPath()).isEqualTo(ENDPOINT_PRODUCTS_LIST);
        assertThat(placeholder.featureFlagId()).isEqualTo(FLAG_SALESFORCE);
    }

    @Test
    void compose_productsListWithoutSourceOfData_keepsLegacySalesforceRouting() {
        LandingPage page = lazyService().compose(def(productsList("pl-legacy", null)), GUEST_WEB);

        DynamicPlaceholder placeholder = (DynamicPlaceholder) page.blocks().get(0);
        assertThat(placeholder.resolutionPath()).isEqualTo(ENDPOINT_PRODUCTS_LIST);
    }

    @Test
    void compose_mixedSources_routeEachBlockIndependently() {
        LandingPage page = lazyService().compose(
                def(productsList("a", "jewel"), productsList("b", "salesforce"), productsList("c", "jewel")),
                GUEST_WEB);

        // Both providers share the endpoint (ADR-029), so the feature flag is what proves each block was
        // routed by its own source_of_data rather than all inheriting one provider's routing.
        assertThat(page.blocks())
                .extracting(block -> ((DynamicPlaceholder) block).featureFlagId())
                .containsExactly(FLAG_JEWEL, FLAG_SALESFORCE, FLAG_JEWEL);
        assertThat(page.blocks())
                .extracting(block -> ((DynamicPlaceholder) block).resolutionPath())
                .containsOnly(ENDPOINT_PRODUCTS_LIST);
    }

    /** V2 shares V1's content type and its {@code salesforce} prefix, but never its flag. */
    @Test
    void compose_salesforceV2BackedProductsList_advertisesItsOwnFlag() {
        LandingPage page = lazyService().compose(
                def(productsList("pl-sf", "salesforce"), productsList("pl-sf2", "salesforce_v2")), GUEST_WEB);

        assertThat(page.blocks())
                .extracting(block -> ((DynamicPlaceholder) block).featureFlagId())
                .containsExactly(FLAG_SALESFORCE, FLAG_SALESFORCE_V2);
    }

    // ── unmapped sources must never inherit another adapter's routing ──────────────────────────────

    @Test
    void compose_unsupportedSource_becomesStaticInsteadOfBeingMisrouted() {
        // recently_viewed has no catalog entry; it must not fall through to the Salesforce adapter.
        LandingPage page = lazyService().compose(def(productsList("pl-rv", "recently_viewed")), GUEST_WEB);

        assertThat(page.blocks()).singleElement().isInstanceOf(StaticBlock.class);
    }

    @Test
    void compose_unsupportedSource_preservesBlockContent() {
        LandingPage page = lazyService().compose(def(productsList("pl-lob", "lob")), GUEST_WEB);

        StaticBlock block = (StaticBlock) page.blocks().get(0);
        assertThat(block.blockId()).isEqualTo("pl-lob");
        assertThat(block.content()).containsEntry("source_of_data", "lob");
    }

    // ── eager resolution picks the resolver matching the source ────────────────────────────────────

    @Test
    void compose_eager_invokesJewelResolverForJewelBackedBlock_notSalesforce() {
        RecordingResolver jewel = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL)));
        RecordingResolver salesforce = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE)));

        eagerService(jewel, salesforce).compose(def(productsList("pl-jewel", "jewel")), GUEST_WEB, Map.of(), 5);

        assertThat(jewel.invoked()).isTrue();
        assertThat(salesforce.invoked()).isFalse();
    }

    @Test
    void compose_eager_invokesSalesforceResolverForSalesforceBackedBlock_notJewel() {
        RecordingResolver jewel = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL)));
        RecordingResolver salesforce = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE)));

        eagerService(jewel, salesforce).compose(def(productsList("pl-sf", "salesforce")), GUEST_WEB, Map.of(), 5);

        assertThat(salesforce.invoked()).isTrue();
        assertThat(jewel.invoked()).isFalse();
    }

    @Test
    void compose_eager_invokesSalesforceV2ResolverForV2BackedBlock_notV1() {
        RecordingResolver salesforce = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE)));
        RecordingResolver salesforceV2 = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.SALESFORCE_V2)));

        eagerService(salesforce, salesforceV2)
                .compose(def(productsList("pl-sf2", "salesforce_v2")), GUEST_WEB, Map.of(), 5);

        assertThat(salesforceV2.invoked()).isTrue();
        assertThat(salesforce.invoked()).isFalse();
    }

    /** groupby is aliased to the Vertex provider rather than left unrouted (ADR-055). */
    @Test
    void compose_eager_groupbyTaggedBlock_invokesVertexResolver() {
        RecordingResolver vertex = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX),
                        BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.GROUPBY)));

        eagerService(vertex).compose(def(productsList("pl-gb", "groupby")), GUEST_WEB, Map.of(), 5);

        assertThat(vertex.invoked()).isTrue();
    }

    @Test
    void compose_eager_resolvedBlockCarriesItsOwnSourcesEndpoint() {
        RecordingResolver jewel = new RecordingResolver(
                Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.JEWEL)));

        LandingPage page = eagerService(jewel)
                .compose(def(productsList("pl-jewel", "jewel")), GUEST_WEB, Map.of(), 5);

        assertThat(((ResolvedBlock) page.blocks().get(0)).resolutionPath()).isEqualTo(ENDPOINT_PRODUCTS_LIST);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────

    private LandingCompositionService lazyService() {
        return new LandingCompositionService(ALL_ENABLED, catalog);
    }

    private LandingCompositionService eagerService(BlockResolver... resolvers) {
        Map<BlockResolutionKey, BlockResolver> byKey = new java.util.HashMap<>();
        for (BlockResolver resolver : resolvers) {
            resolver.resolutionKeys().forEach(key -> byKey.put(key, resolver));
        }
        return new LandingCompositionService(ALL_ENABLED, catalog, new BlockResolverRegistry(byKey), 5);
    }

    private static LandingDefinition def(BlockDefinition... blocks) {
        return LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(blocks));
    }

    /** Mirrors the live CMS shape: source_of_data lives in the raw attributes, not a named field. */
    private static BlockDefinition productsList(String uid, String source) {
        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("source_of_data", source);
        return new BlockDefinition(uid, "products_list", null, true, true, attributes);
    }

    /** Records whether the composition service picked this strategy. */
    private static final class RecordingResolver implements BlockResolver {

        private final Set<BlockResolutionKey> keys;
        private final AtomicBoolean invoked = new AtomicBoolean(false);

        RecordingResolver(Set<BlockResolutionKey> keys) {
            this.keys = keys;
        }

        boolean invoked() {
            return invoked.get();
        }

        @Override
        public Set<BlockResolutionKey> resolutionKeys() {
            return keys;
        }

        @Override
        public ResolvedContent resolve(BlockResolutionContext context) {
            invoked.set(true);
            return new ResolvedContent(Map.of("blockId", context.blockId()));
        }
    }
}
