package com.liverpool.ms_clp_blp.landing.adapters.contentstack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.clients.contentstack.ContentSourceRequest;
import com.liverpool.ms_clp_blp.clients.contentstack.ContentSourceRouter;
import com.liverpool.ms_clp_blp.landing.errors.LandingDefinitionNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.BlockDefinition;
import com.liverpool.ms_clp_blp.landing.models.ContentQuery;
import com.liverpool.ms_clp_blp.landing.models.LandingDefinition;
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.shared.errors.BrandNotSupportedException;
import com.liverpool.ms_clp_blp.shared.errors.ContentServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins how a raw {@code page_blp}/{@code page_clp} entry becomes a {@link LandingDefinition}: which keys
 * hold each section, what is stripped, what passes through — digital_bff's {@code getContent} rules.
 */
class LandingContentClientTest {

    private static final ContentQuery BLP = new ContentQuery("LP", "es-mx", PageType.BLP, "catst26617625");
    private static final ContentQuery CLP = new ContentQuery("LP", "es-mx", PageType.CLP, "cat5020010");

    private ContentSourceRouter router;
    private LandingContentClient client;

    @BeforeEach
    void setUp() {
        router = mock(ContentSourceRouter.class);
        client = new LandingContentClient(router,
                new CmsProperties("LP", "page_blp", "page_clp", "category_redirect"), new BlockPayloadNormalizer());
    }

    @Test
    void blp_readsTheLayoutGlobalField_andStripsTheAppOnlySections() {
        Map<String, Object> entry = entry();
        entry.put("layout", Map.of("blocks", List.of(
                Map.of("container", Map.of("_metadata", Map.of("uid", "cs1"), "title", "Grid")),
                Map.of("product_list", Map.of("product_list", List.of(Map.of(
                        "_content_type_uid", "products_list", "uid", "blt-pl", "source_of_data", "vertex")))))));
        entry.put("apps_top_content", List.of());
        entry.put("apps_products", List.of());
        entry.put("apps_article", Map.of());
        entry.put("content", "legacy");
        when(router.fetch(any())).thenReturn(entry);

        LandingDefinition definition = client.fetchDefinition(BLP);

        assertThat(definition.categoryId()).isEqualTo("catst26617625");
        assertThat(definition.sections()).containsOnlyKeys(PageSection.BLOCKS);
        assertThat(definition.sections().get(PageSection.BLOCKS))
                .extracting(BlockDefinition::contentTypeUid)
                .containsExactly("container", "products_list");
        assertThat(definition.entryMetadata())
                .containsKeys("uid", "category_id", "header", "events")
                .doesNotContainKeys("layout", "apps_top_content", "apps_products", "apps_article", "content");
        verify(router).fetch(new ContentSourceRequest("page_blp", "es-mx", "catst26617625", "LP"));
    }

    @Test
    void clp_readsTopAndBottomContentArrays_andKeepsItsAppKeys() {
        Map<String, Object> entry = entry();
        entry.put("top_content", List.of(Map.of("banner_slider", Map.of("title", "Top"))));
        entry.put("bottom_content", List.of(Map.of("rich_text", Map.of("text", "<p>SEO</p>"))));
        entry.put("apps_products", List.of());
        when(router.fetch(any())).thenReturn(entry);

        LandingDefinition definition = client.fetchDefinition(CLP);

        assertThat(definition.sections()).containsOnlyKeys(PageSection.TOP_CONTENT, PageSection.BOTTOM_CONTENT);
        assertThat(definition.sections().get(PageSection.TOP_CONTENT))
                .extracting(BlockDefinition::contentTypeUid).containsExactly("banner_slider");
        assertThat(definition.entryMetadata()).containsKey("apps_products")
                .doesNotContainKeys("top_content", "bottom_content");
        verify(router).fetch(new ContentSourceRequest("page_clp", "es-mx", "cat5020010", "LP"));
    }

    @Test
    void aFlatBlocksArray_isTheMainSectionToo() {
        Map<String, Object> entry = entry();
        entry.put("blocks", List.of(Map.of("heading", Map.of("title", "Marcas"))));
        when(router.fetch(any())).thenReturn(entry);

        assertThat(client.fetchDefinition(BLP).sections().get(PageSection.BLOCKS))
                .extracting(BlockDefinition::contentTypeUid).containsExactly("heading");
    }

    @Test
    void aSectionOfTheWrongShape_isAnEmptySectionRatherThanAnError() {
        Map<String, Object> entry = entry();
        entry.put("layout", "not-a-section");
        entry.put("category_id", "  ");
        when(router.fetch(any())).thenReturn(entry);

        LandingDefinition definition = client.fetchDefinition(BLP);

        assertThat(definition.sections().get(PageSection.BLOCKS)).isEmpty();
        assertThat(definition.categoryId()).isNull();
    }

    @Test
    void noEntryForTheCategory_isThisSlicesOwn404() {
        when(router.fetch(any())).thenThrow(new ContentstackEntryNotFoundException("none", "query"));

        assertThatThrownBy(() -> client.fetchDefinition(CLP))
                .isInstanceOf(LandingDefinitionNotFoundException.class)
                .hasMessageContaining("cat5020010");
    }

    @Test
    void brandAndOutageFailures_propagateUnchanged() {
        BrandNotSupportedException brand = new BrandNotSupportedException("no", "detail", null);
        doThrow(brand).when(router).fetch(any());
        assertThatThrownBy(() -> client.fetchDefinition(BLP)).isSameAs(brand);

        ContentServiceUnavailableException down = new ContentServiceUnavailableException("down", "d", null);
        doThrow(down).when(router).fetch(any());
        assertThatThrownBy(() -> client.fetchDefinition(BLP)).isSameAs(down);
    }

    @Test
    void anUnexpectedFailure_isAnOutage() {
        when(router.fetch(any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> client.fetchDefinition(BLP)).isInstanceOf(ContentServiceUnavailableException.class);
    }

    @Test
    void blankBrandAndContentTypes_fallBackToTheDefaults() {
        LandingContentClient defaults = new LandingContentClient(router,
                new CmsProperties("SB", null, " ", null), new BlockPayloadNormalizer());
        when(router.fetch(any())).thenReturn(entry());

        defaults.fetchDefinition(new ContentQuery(" ", "es-mx", PageType.CLP, "cat1"));

        verify(router).fetch(new ContentSourceRequest("page_clp", "es-mx", "cat1", "SB"));
    }

    private static Map<String, Object> entry() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("uid", "blt0ff08f5fb7e51522");
        entry.put("category_id", "catst26617625");
        entry.put("header", Map.of("_content_type_uid", "header"));
        entry.put("events", List.of());
        return entry;
    }
}
