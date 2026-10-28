package com.liverpool.ms_clp_blp.landing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.ResolvedContent;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;
import com.liverpool.ms_clp_blp.landing.ports.BlockResolver;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a landing page adds to the Home's composition: several sections composed as one page, and the
 * footer's audience-filtered menu.
 */
class LandingCompositionSectionsTest {

    private static final SessionContext GUEST = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext LOGGED = new SessionContext(true, "LP", "WEB", "es-mx");
    private static final BlockResolutionKey VERTEX = BlockResolutionKey.of(BlockType.PRODUCTS_LIST, SourceOfData.VERTEX);
    private static final BlockResolutionCatalog CATALOG = new BlockResolutionCatalog(Map.of(
            VERTEX, BlockResolution.batch("/clp/blocks/products-list", "vertex")));

    /** The eager offset counts across sections in order: top, main, bottom. */
    @Test
    void theEagerOffsetIsPageWide_acrossSectionsInOrder() {
        LandingCompositionService service = new LandingCompositionService((flag, brand) -> true, CATALOG,
                registry(), 2);
        Map<PageSection, List<BlockDefinition>> sections = new LinkedHashMap<>();
        sections.put(PageSection.BOTTOM_CONTENT, List.of(carousel("bottom-1")));
        sections.put(PageSection.TOP_CONTENT, List.of(carousel("top-1")));
        sections.put(PageSection.BLOCKS, List.of(carousel("main-1"), carousel("main-2")));

        LandingPage page = service.compose(new LandingDefinition(PageType.CLP, "es-mx", "c1", Map.of(), sections), GUEST);

        assertThat(page.blocks(PageSection.TOP_CONTENT)).singleElement().isInstanceOf(ResolvedBlock.class);
        assertThat(page.blocks(PageSection.BLOCKS).get(0)).isInstanceOf(ResolvedBlock.class);
        assertThat(page.blocks(PageSection.BLOCKS).get(1)).isInstanceOf(DynamicPlaceholder.class);
        assertThat(page.blocks(PageSection.BOTTOM_CONTENT)).singleElement().isInstanceOf(DynamicPlaceholder.class);
        assertThat(page.sections().keySet())
                .containsExactly(PageSection.TOP_CONTENT, PageSection.BLOCKS, PageSection.BOTTOM_CONTENT);
    }

    /** The analytics counter is page-wide too, so it continues from one section into the next. */
    @Test
    void theAnalyticsIndexContinuesAcrossSections() {
        LandingCompositionService service = new LandingCompositionService((flag, brand) -> true, CATALOG);
        Map<PageSection, List<BlockDefinition>> sections = new LinkedHashMap<>();
        sections.put(PageSection.TOP_CONTENT, List.of(withIndexEvent("t1")));
        sections.put(PageSection.BLOCKS, List.of(withIndexEvent("b1")));

        LandingPage page = service.compose(new LandingDefinition(PageType.CLP, "es-mx", null, Map.of(), sections), GUEST);

        assertThat(indexOf(page.blocks(PageSection.TOP_CONTENT).getFirst())).isEqualTo(1);
        assertThat(indexOf(page.blocks(PageSection.BLOCKS).getFirst())).isEqualTo(2);
    }

    @Test
    void theFooterMenu_isFilteredForTheAudience() {
        LandingCompositionService service = new LandingCompositionService((flag, brand) -> true, CATALOG);
        Map<String, Object> footer = Map.of("uid", "blt-footer", "menu_items", List.of(
                Map.of("label", "Mis compras", "audience_filter", "logged"),
                Map.of("label", "Regístrate", "audience_filter", "guest"),
                Map.of("label", "Ayuda")));
        LandingDefinition definition = new LandingDefinition(PageType.BLP, "es-mx", "c1",
                Map.of("footer", footer), Map.of(PageSection.BLOCKS, List.of()));

        assertThat(menuLabels(service.compose(definition, GUEST))).containsExactly("Regístrate", "Ayuda");
        assertThat(menuLabels(service.compose(definition, LOGGED))).containsExactly("Mis compras", "Ayuda");
    }

    @Test
    void aFooterWithNothingToFilter_isPassedThroughUntouched() {
        LandingCompositionService service = new LandingCompositionService((flag, brand) -> true, CATALOG);
        Map<String, Object> footer = Map.of("menu_items", List.of(Map.of("label", "Ayuda"), "not-a-map"));
        LandingDefinition definition = new LandingDefinition(PageType.BLP, "es-mx", "c1",
                Map.of("footer", footer, "header", Map.of()), Map.of());

        assertThat(service.compose(definition, GUEST).entryMetadata().get("footer")).isEqualTo(footer);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static BlockResolverRegistry registry() {
        BlockResolver resolver = new BlockResolver() {
            @Override
            public Set<BlockResolutionKey> resolutionKeys() {
                return Set.of(VERTEX);
            }

            @Override
            public ResolvedContent resolve(BlockResolutionContext context) {
                return new ResolvedContent(Map.of("products", List.of()));
            }
        };
        return new BlockResolverRegistry(Map.of(VERTEX, resolver));
    }

    private static BlockDefinition carousel(String uid) {
        return new BlockDefinition(uid, "products_list", null, true, true, Map.of("source_of_data", "vertex"));
    }

    private static BlockDefinition withIndexEvent(String uid) {
        return new BlockDefinition(uid, "container", null, true, true, Map.of("events", List.of(Map.of(
                "customData", List.of(Map.of("type", "index", "source", "bff"))))));
    }

    @SuppressWarnings("unchecked")
    private static Object indexOf(Object block) {
        Map<String, Object> content = ((StaticBlock) block).content();
        Map<String, Object> event = (Map<String, Object>) ((List<Object>) content.get("events")).getFirst();
        return ((Map<String, Object>) ((List<Object>) event.get("customData")).getFirst()).get("value");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> menuLabels(LandingPage page) {
        Map<String, Object> footer = (Map<String, Object>) page.entryMetadata().get("footer");
        return ((List<Map<String, Object>>) footer.get("menu_items")).stream().map(item -> (Object) item.get("label")).toList();
    }
}
