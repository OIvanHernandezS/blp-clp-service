package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.BlockKind;
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
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.landing.ports.FeatureFlagPort;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LandingCompositionServiceTest {

    @Mock
    private FeatureFlagPort featureFlagPort;

    private LandingCompositionService service;

    private static final String FLAG_PRODUCTS_LIST = "products-list-salesforce";
    private static final String ENDPOINT = "/blp/blocks/products-list";
    private static final SessionContext GUEST_WEB = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_WEB  = new SessionContext(true,  "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_APPS = new SessionContext(true,  "LP", "APP", "es-mx");

    @BeforeEach
    void setUp() {
        BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST), BlockResolution.post(ENDPOINT, FLAG_PRODUCTS_LIST)));
        service = new LandingCompositionService(featureFlagPort, catalog);
    }

    // ── production block pattern (no audienceFilter, no channel flags) ───────────────────────────

    @Test
    void compose_productionBlock_nullAudienceAndAbsentChannelFlags_isVisible() {
        // Mirrors what ContentServiceClient.toBlockDefinition() produces for production blocks:
        // audienceFilter=null (→ ALL), enabledOnWeb=true (default), enabledOnApps=true (default).
        BlockDefinition block = new BlockDefinition("uid-1", "hero_banner_slider", null, true, true, Map.of());
        LandingDefinition def = defWithBlocks(block);

        assertThat(service.compose(def, GUEST_WEB).blocks()).hasSize(1);
        assertThat(service.compose(def, AUTH_WEB).blocks()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "container", "container_guest", "hero_banner_slider", "heading", "rich_text",
            "button_list", "banner_slider", "card_slider"
    })
    void compose_productionBlockTypes_classifiedAsStatic(String contentTypeUid) {
        BlockDefinition block = new BlockDefinition("uid", contentTypeUid, null, true, true, Map.of());
        LandingDefinition def = defWithBlocks(block);

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
        assertThat(((StaticBlock) page.blocks().get(0)).blockType().kind()).isEqualTo(BlockKind.STATIC);
    }

    // ── static blocks ────────────────────────────────────────────────────────────────────────────

    @Test
    void compose_staticContainerBlock_returnedAsStaticBlock() {
        LandingDefinition def = defWithBlocks(banner("uid-1", "all", true, true));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
        StaticBlock block = (StaticBlock) page.blocks().get(0);
        assertThat(block.blockId()).isEqualTo("uid-1");
        assertThat(block.blockType()).isEqualTo(BlockType.CONTAINER);
        assertThat(block.blockType().kind()).isEqualTo(BlockKind.STATIC);
    }

    @Test
    void compose_unknownContentTypeUid_treatedAsStaticPassthrough() {
        LandingDefinition def = defWithBlocks(unknown("uid-x", "all"));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
        assertThat(((StaticBlock) page.blocks().get(0)).blockType()).isEqualTo(BlockType.UNKNOWN);
    }

    // ── dynamic blocks ───────────────────────────────────────────────────────────────────────────

    @Test
    void compose_dynamicBlockWithFlagEnabled_returnsAvailablePlaceholder() {
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST), any())).thenReturn(true);
        LandingDefinition def = defWithBlocks(productsList("pl-1", "all"));

        LandingPage page = service.compose(def, AUTH_WEB);

        DynamicPlaceholder ph = assertDynamic(page, 0);
        assertThat(ph.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(ph.resolutionPath()).isEqualTo(ENDPOINT);
        assertThat(ph.featureFlagId()).isEqualTo(FLAG_PRODUCTS_LIST);
        assertThat(ph.blockId()).isEqualTo("pl-1");
    }

    @Test
    void compose_dynamicBlockWithFlagDisabled_returnsDisabledPlaceholder() {
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST), any())).thenReturn(false);
        LandingDefinition def = defWithBlocks(productsList("pl-2", "all"));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(assertDynamic(page, 0).status()).isEqualTo(DynamicBlockStatus.DISABLED);
    }

    /**
     * {@link DynamicBlockStatus} is shared with the {@code /blp|clp/blocks/*} resolution responses, but the
     * two contracts use disjoint subsets of it: a placeholder answers "should I call the endpoint?" and is
     * never {@link DynamicBlockStatus#EMPTY}, because nothing has been resolved yet (ADR-043). Leaking
     * {@code EMPTY} here would tell the frontend to drop a block it has not even tried to fetch.
     */
    @Test
    void compose_placeholderStatus_isNeverEmpty() {
        LandingDefinition def = defWithBlocks(
                productsList("pl-enabled", "all"),
                productsList("pl-disabled", "all"));
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST), any())).thenReturn(true, false);

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).allSatisfy(block -> {
            if (block instanceof DynamicPlaceholder placeholder) {
                assertThat(placeholder.status()).isNotEqualTo(DynamicBlockStatus.EMPTY);
            }
        });
    }

    // ── eager resolution: strategy drop-on-null ──────────────────────────────────────────────────

    @Test
    void compose_eagerResolverReturnsNull_dropsBlockButKeepsSiblingsInOrder() {
        when(featureFlagPort.isEnabled(eq(FLAG_PRODUCTS_LIST), any())).thenReturn(true);
        // Resolver that drops its block by resolving to null.
        BlockResolver droppingResolver = new BlockResolver() {
            @Override
            public Set<BlockResolutionKey> resolutionKeys() {
                return Set.of(BlockResolutionKey.of(BlockType.PRODUCTS_LIST));
            }

            @Override
            public ResolvedContent resolve(BlockResolutionContext context) {
                return null; // drop
            }
        };
        BlockResolutionCatalog catalog = new BlockResolutionCatalog(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST), BlockResolution.post(ENDPOINT, FLAG_PRODUCTS_LIST)));
        BlockResolverRegistry registry = new BlockResolverRegistry(Map.of(
                BlockResolutionKey.of(BlockType.PRODUCTS_LIST), droppingResolver));
        // Offset 3 → the first three blocks resolve eagerly.
        LandingCompositionService eagerService =
                new LandingCompositionService(featureFlagPort, catalog, registry, 3);

        LandingDefinition def = defWithBlocks(
                banner("visible-1", "all", true, true),
                productsList("pl-drop", "all"),
                banner("visible-2", "all", true, true));

        LandingPage page = eagerService.compose(def, AUTH_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("visible-1", "visible-2");
    }

    // ── audience filtering ───────────────────────────────────────────────────────────────────────

    @Test
    void compose_loggedOnlyBlock_hiddenForGuestSession() {
        LandingDefinition def = defWithBlocks(banner("uid-auth", "logged", true, true));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_loggedOnlyBlock_visibleForAuthenticatedSession() {
        LandingDefinition def = defWithBlocks(banner("uid-auth", "logged", true, true));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).hasSize(1);
    }

    @Test
    void compose_guestOnlyBlock_hiddenForAuthenticatedSession() {
        LandingDefinition def = defWithBlocks(banner("uid-guest", "guest", true, true));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_guestOnlyBlock_visibleForGuestSession() {
        LandingDefinition def = defWithBlocks(banner("uid-guest", "guest", true, true));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
    }

    @Test
    void compose_allAudienceBlock_visibleForBothSessions() {
        LandingDefinition def = defWithBlocks(banner("uid-all", "all", true, true));

        assertThat(service.compose(def, GUEST_WEB).blocks()).hasSize(1);
        assertThat(service.compose(def, AUTH_WEB).blocks()).hasSize(1);
    }

    // ── container_guest composition-time login gate (Rule 19 reference: legacy
    // container_guest.strategy.ts — distinct from the generic audience_filter mechanism) ──────────

    @Test
    void compose_containerGuestBlock_droppedForAuthenticatedSession() {
        LandingDefinition def = defWithBlocks(
                new BlockDefinition("uid-guest", "container_guest", null, true, true, Map.of()));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_containerGuestBlock_visibleForGuestSession() {
        LandingDefinition def = defWithBlocks(
                new BlockDefinition("uid-guest", "container_guest", null, true, true, Map.of()));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).hasSize(1);
        assertThat(page.blocks().get(0)).isInstanceOf(StaticBlock.class);
    }

    @Test
    void compose_containerGuestBlock_droppedForAuthenticatedSession_doesNotShiftRemainingOrder() {
        LandingDefinition def = defWithBlocks(
                banner("visible-1", "all", true, true),
                new BlockDefinition("uid-guest", "container_guest", null, true, true, Map.of()),
                banner("visible-2", "all", true, true));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("visible-1", "visible-2");
    }

    // ── channel visibility ───────────────────────────────────────────────────────────────────────

    @Test
    void compose_webDisabledBlock_hiddenForWebChannel() {
        LandingDefinition def = defWithBlocks(banner("uid-noweb", "all", false, true));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).isEmpty();
    }

    @Test
    void compose_appsDisabledBlock_hiddenForAppsChannel() {
        LandingDefinition def = defWithBlocks(banner("uid-noapps", "all", true, false));

        LandingPage page = service.compose(def, AUTH_APPS);

        assertThat(page.blocks()).isEmpty();
    }

    /**
     * An absent channel is never how the real request path reaches this method
     * ({@code SessionContextAdapter} always defaults to the literal string {@code "WEB"}), but
     * {@code Channel.from(null)} resolves to {@code WEB} — pin that choice down explicitly so it
     * cannot drift unnoticed.
     */
    @Test
    void compose_nullChannel_isTreatedAsWeb() {
        LandingDefinition def = defWithBlocks(banner("uid-noweb", "all", false, true));
        SessionContext nullChannel = new SessionContext(true, "LP", null, "es-mx");

        LandingPage page = service.compose(def, nullChannel);

        assertThat(page.blocks()).isEmpty();
    }

    /**
     * Kiosk and point-of-sale clients render the web experience, so they read the web toggle: a block
     * hidden on web is hidden there too, and a block hidden only on apps stays visible.
     */
    @ParameterizedTest
    @ValueSource(strings = {"KIOSCO", "POS", "kiosco"})
    void compose_kioscoAndPosChannels_followTheWebToggle(String channel) {
        SessionContext session = new SessionContext(true, "LP", channel, "es-mx");

        assertThat(service.compose(defWithBlocks(banner("uid-noweb", "all", false, true)), session).blocks())
                .isEmpty();
        assertThat(service.compose(defWithBlocks(banner("uid-noapps", "all", true, false)), session).blocks())
                .hasSize(1);
    }

    /**
     * Unlike the other channel-branching call sites, this guard is binary — web-like vs. not — so it cannot tell the legacy plural {@code "APPS"} apart from the
     * real {@code "APP"}; both still land in the apps branch here. Pre-existing, not a regression from
     * the APP/APPS fix, but worth pinning down explicitly so a future reader does not mistake it for
     * an oversight.
     */
    @Test
    void compose_legacyPluralAppsChannel_stillTreatedAsApps() {
        LandingDefinition def = defWithBlocks(banner("uid-noapps", "all", true, false));
        SessionContext legacyPlural = new SessionContext(true, "LP", "APPS", "es-mx");

        LandingPage page = service.compose(def, legacyPlural);

        assertThat(page.blocks()).isEmpty();
    }

    // ── ordering ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void compose_preservesBlockOrder() {
        LandingDefinition def = defWithBlocks(
                banner("first", "all", true, true),
                banner("second", "all", true, true),
                banner("third", "all", true, true));

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("first", "second", "third");
    }

    @Test
    void compose_hiddenBlockDoesNotShiftRemainingOrder() {
        LandingDefinition def = defWithBlocks(
                banner("visible-1", "all", true, true),
                banner("guest-only", "guest", true, true),   // hidden for auth
                banner("visible-2", "all", true, true));

        LandingPage page = service.compose(def, AUTH_WEB);

        assertThat(page.blocks()).extracting(b -> ((StaticBlock) b).blockId())
                .containsExactly("visible-1", "visible-2");
    }

    @Test
    void compose_setsLocaleFromDefinition() {
        LandingDefinition def = LandingDefinition.ofBlocks(PageType.BLP, "pt-br", List.of());

        LandingPage page = service.compose(def, GUEST_WEB);

        assertThat(page.locale()).isEqualTo("pt-br");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static LandingDefinition defWithBlocks(BlockDefinition... blocks) {
        return LandingDefinition.ofBlocks(PageType.BLP, "es-mx", List.of(blocks));
    }

    private static BlockDefinition banner(String uid, String audience, boolean web, boolean apps) {
        return new BlockDefinition(uid, "container", audience, web, apps, Map.of());
    }

    private static BlockDefinition productsList(String uid, String audience) {
        return new BlockDefinition(uid, "products_list", audience, true, true, Map.of());
    }

    private static BlockDefinition unknown(String uid, String audience) {
        return new BlockDefinition(uid, "unrecognised_type", audience, true, true, Map.of());
    }

    private DynamicPlaceholder assertDynamic(LandingPage page, int index) {
        assertThat(page.blocks().get(index)).isInstanceOf(DynamicPlaceholder.class);
        return (DynamicPlaceholder) page.blocks().get(index);
    }
}
