package com.liverpool.ms_clp_blp.landing;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.errors.LandingDefinitionNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.SelectedStore;
import com.liverpool.ms_clp_blp.landing.ports.CategoryPort;
import com.liverpool.ms_clp_blp.landing.ports.CategoryRedirectPort;
import com.liverpool.ms_clp_blp.landing.ports.ContentPort;
import com.liverpool.ms_clp_blp.landing.ports.FavoriteStorePort;
import com.liverpool.ms_clp_blp.shared.errors.ValidationException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how the use case assembles a landing page: which entry it reads, which best-effort reads it
 * makes, and when {@code categoryData} is attached — digital_bff's {@code getContent} rules for
 * {@code page-blp}/{@code page-clp}.
 */
class LandingUseCasesTest {

    private static final SessionContext SESSION = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final String PATH = "/tienda/esteelauder/catst26617625";
    private static final CategoryData CATEGORY = new CategoryData("CATST26617625", "Estee Lauder", "Estee Lauder",
            "/tienda/estee-lauder/catst26617625", false, List.of(), null, List.of(), "home > Estee Lauder", null);

    private ContentPort contentPort;
    private CategoryPort categoryPort;
    private CategoryRedirectPort redirectPort;
    private FavoriteStorePort storePort;
    private LandingCompositionService blpComposition;
    private LandingCompositionService clpComposition;
    private LandingUseCases useCases;

    @BeforeEach
    void setUp() {
        contentPort = mock(ContentPort.class);
        categoryPort = mock(CategoryPort.class);
        redirectPort = mock(CategoryRedirectPort.class);
        storePort = mock(FavoriteStorePort.class);
        blpComposition = mock(LandingCompositionService.class);
        clpComposition = mock(LandingCompositionService.class);
        useCases = new LandingUseCases(contentPort, categoryPort, redirectPort, storePort,
                new LandingCompositions(Map.of(PageType.BLP, blpComposition, PageType.CLP, clpComposition)));
        when(categoryPort.findCategory(any(), any())).thenReturn(Optional.of(CATEGORY));
        when(redirectPort.findActiveRedirect(any(), any(), any())).thenReturn(Optional.empty());
        when(blpComposition.compose(any(), any(), any(), any(), any())).thenReturn(page(PageType.BLP));
        when(clpComposition.compose(any(), any(), any(), any(), any())).thenReturn(page(PageType.CLP));
    }

    @Test
    void getLandingPage_readsTheEntryByTheLastPathSegment() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));

        useCases.getLandingPage(query(PageType.BLP, PATH, null));

        verify(contentPort).fetchDefinition(new ContentQuery("LP", "es-mx", PageType.BLP, "catst26617625"));
    }

    @Test
    void getLandingPage_attachesCategoryDataWhenTheEntryNamesItsCategory() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));

        LandingPage result = useCases.getLandingPage(query(PageType.BLP, PATH, null));

        assertThat(result.categoryData()).isEqualTo(CATEGORY);
    }

    @Test
    void getLandingPage_activeRedirect_isAttachedWithHasRedirectFirst() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));
        when(redirectPort.findActiveRedirect("LP", "es-mx", "catst26617625"))
                .thenReturn(Optional.of(Map.of("uid", "blt-redirect", "is_active", true)));

        LandingPage result = useCases.getLandingPage(query(PageType.BLP, PATH, null));

        assertThat(result.categoryData().redirectContent())
                .containsEntry("hasRedirect", true)
                .containsEntry("uid", "blt-redirect");
        assertThat(result.categoryData().redirectContent().keySet().iterator().next()).isEqualTo("hasRedirect");
    }

    /** A CLP whose indexer lookup fails is still served — the deliberate difference from digital_bff. */
    @Test
    void getLandingPage_categoryUnavailable_servesThePageWithoutCategoryData() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.CLP, "cat5020010"));
        when(categoryPort.findCategory(any(), any())).thenReturn(Optional.empty());

        LandingPage result = useCases.getLandingPage(query(PageType.CLP, "/tienda/belleza/cat5020010", null));

        assertThat(result.categoryData()).isNull();
        verify(redirectPort, never()).findActiveRedirect(any(), any(), any());
    }

    @Test
    void getLandingPage_clpEntryWithoutCategory_attachesNoCategoryData() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.CLP, null));

        assertThat(useCases.getLandingPage(query(PageType.CLP, "/tienda/x/cat1", null)).categoryData()).isNull();
    }

    /** digital_bff answered a BLP entry without category_id with a 404. */
    @Test
    void getLandingPage_blpEntryWithoutCategory_isNotFound() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, null));

        assertThatThrownBy(() -> useCases.getLandingPage(query(PageType.BLP, PATH, null)))
                .isInstanceOf(LandingDefinitionNotFoundException.class);
    }

    @Test
    void getLandingPage_pathWithoutASegment_isAValidationError() {
        assertThatThrownBy(() -> useCases.getLandingPage(query(PageType.BLP, "/", null)))
                .isInstanceOf(ValidationException.class);
        verify(contentPort, never()).fetchDefinition(any());
    }

    @Test
    void getLandingPage_routesToThePageTypesOwnComposition() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.CLP, "cat1"));

        useCases.getLandingPage(query(PageType.CLP, "/tienda/x/cat1", null));

        verify(clpComposition).compose(any(), eq(SESSION), any(), isNull(), isNull());
        verify(blpComposition, never()).compose(any(), any(), any(), any(), any());
    }

    @Test
    void getLandingPage_favoriteStoreCookie_isResolvedAndHandedToComposition() {
        SelectedStore store = new SelectedStore(7L, "Liverpool Santa Fe");
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));
        when(storePort.findStore("LP", "7")).thenReturn(Optional.of(store));

        useCases.getLandingPage(query(PageType.BLP, PATH, " 7 "));

        verify(blpComposition).compose(any(), any(), any(), any(), eq(store));
    }

    @Test
    void getLandingPage_noFavoriteStoreCookie_neverAsksTheStoreService() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));

        useCases.getLandingPage(query(PageType.BLP, PATH, ""));

        verify(storePort, never()).findStore(any(), any());
        verify(blpComposition).compose(any(), any(), any(), any(), isNull());
    }

    @Test
    void getLandingPage_aBestEffortPortThatThrows_degradesInsteadOfFailingThePage() {
        when(contentPort.fetchDefinition(any())).thenReturn(definition(PageType.BLP, "catst26617625"));
        when(categoryPort.findCategory(any(), any())).thenThrow(new IllegalStateException("defect"));

        assertThat(useCases.getLandingPage(query(PageType.BLP, PATH, null)).categoryData()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private static LandingPageQuery query(PageType type, String path, String favoriteStore) {
        return new LandingPageQuery(type, "es-mx", path, SESSION, Map.of(), null, favoriteStore);
    }

    private static LandingDefinition definition(PageType type, String categoryId) {
        return new LandingDefinition(type, "es-mx", categoryId, Map.of("uid", "blt1"),
                Map.of(PageSection.BLOCKS, List.of()));
    }

    private static LandingPage page(PageType type) {
        return new LandingPage(type, "es-mx", Map.of(), Map.of(PageSection.BLOCKS, List.of()), null);
    }
}
