package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.landing.models.BlockType;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.CategoryMetadata;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.DynamicPlaceholder;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LoadStrategy;
import com.liverpool.ms_clp_blp.landing.models.PageSection;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.models.ResolvedBlock;
import com.liverpool.ms_clp_blp.landing.models.StaticBlock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the mapper reproduces digital_bff's {@code page-blp}/{@code page-clp} superset shape: a root
 * passthrough with each composed section ({@code blocks}, {@code top_content}, {@code bottom_content})
 * as an array of flat blocks keyed by {@code _content_type_uid}, then {@code categoryData}; no
 * {@code blockType}/{@code blockId}/{@code content} envelope; dynamic blocks carry their CS content
 * plus the additive lazy-resolution fields.
 */
class LandingPageMapperTest {

    private LandingPageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new LandingPageMapper(new BlockContentNormalizer(), new CategoryDataMapper());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> blocksOf(LandingPageResponse response) {
        return (List<Map<String, Object>>) response.root().get("blocks");
    }

    // ── static blocks ────────────────────────────────────────────────────────────────────────────

    @Test
    void toResponse_staticBlock_isFlatWithNoEnvelope() {
        StaticBlock block = new StaticBlock("uid-1", BlockType.CONTAINER,
                Map.of("_content_type_uid", "container", "uid", "uid-1", "title", "Hello"));

        Map<String, Object> dto = blocksOf(mapper.toResponse(page(block))).get(0);

        assertThat(dto)
                .containsEntry("_content_type_uid", "container")
                .containsEntry("uid", "uid-1")
                .containsEntry("title", "Hello");
        // ms_params is a dynamic-block contract element; a static block has no endpoint to post to, so
        // emitting it here would tell the frontend there is something to call.
        assertThat(dto).doesNotContainKeys("kind", "blockType", "blockId", "content", "url",
                "resolutionPath", "loadStrategy", "status", "ms_params");
    }

    @Test
    void toResponse_staticBlock_retainsCmsMetadata() {
        StaticBlock block = new StaticBlock("uid-2", BlockType.CONTAINER, Map.of(
                "_content_type_uid", "container", "_version", 3, "publish_details", Map.of("locale", "es-mx"),
                "_metadata", Map.of("uid", "uid-2"), "audience_filter", "ALL"));

        Map<String, Object> dto = blocksOf(mapper.toResponse(page(block))).get(0);

        assertThat(dto).containsKeys("_version", "publish_details", "_metadata", "audience_filter");
    }

    // ── dynamic placeholders (lazy) ──────────────────────────────────────────────────────────────

    @Test
    void toResponse_dynamicPlaceholder_carriesCsContentAndLazyFields() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-3", BlockType.PRODUCTS_LIST,
                Map.of("_content_type_uid", "products_list", "products_list_title", "Lo más vendido"),
                "/blp/blocks/products-list", "fallback-payload",
                "products-list-salesforce", DynamicBlockStatus.AVAILABLE, "POST", LoadStrategy.LAZY, false,
                Map.of("uid", "uid-3", "source_of_data", "salesforce",
                        "salesforce_carousel", "CMSTePuedeGustar"));

        Map<String, Object> dto = blocksOf(mapper.toResponse(page(ph))).get(0);

        assertThat(dto)
                .containsEntry("_content_type_uid", "products_list")
                .containsEntry("products_list_title", "Lo más vendido")
                .containsEntry("resolutionPath", "/blp/blocks/products-list")
                // Batch endpoint: the advertised url is the bare path, no block id (ADR-029).
                .containsEntry("url", "/blp/blocks/products-list")
                .containsEntry("httpMethod", "POST")
                .containsEntry("loadStrategy", "LAZY")
                .containsEntry("fallback", "fallback-payload")
                .containsEntry("featureFlagId", "products-list-salesforce")
                .containsEntry("status", "AVAILABLE")
                // What the frontend re-posts to `url`, instead of this whole block.
                .containsEntry("ms_params", Map.of("uid", "uid-3", "source_of_data", "salesforce",
                        "salesforce_carousel", "CMSTePuedeGustar"));
        // The strategy-populated detail is NOT inlined for a lazy block — the frontend fetches it via `url`.
        // `kind` is intentionally not emitted — `loadStrategy != null` already marks a dynamic block.
        assertThat(dto).doesNotContainKeys("products", "blockType", "blockId", "kind", "content");
    }

    /**
     * The advertised {@code url} must be callable as-is. Every endpoint in the catalog is addressed on
     * its bare path ({@code products_list} is a batch endpoint), so appending an id would advertise a
     * 404 (ADR-029). The path-variable form is still supported by {@code BlockResolution.post}, and
     * {@code BlockResolutionTest} covers that derivation directly.
     */
    @Test
    void toResponse_batchEndpoint_advertisesUrlWithoutABlockId() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-jewel", BlockType.PRODUCTS_LIST,
                Map.of("_content_type_uid", "products_list", "source_of_data", "jewel"),
                "/blp/blocks/products-list", null, "jewel",
                DynamicBlockStatus.AVAILABLE, "POST", LoadStrategy.LAZY, false,
                Map.of("uid", "uid-jewel", "source_of_data", "jewel"));

        assertThat(blocksOf(mapper.toResponse(page(ph))).get(0))
                .containsEntry("resolutionPath", "/blp/blocks/products-list")
                .containsEntry("url", "/blp/blocks/products-list");
    }

    @Test
    void toResponse_disabledPlaceholder_omitsNullFallbackAndFlag() {
        DynamicPlaceholder ph = new DynamicPlaceholder(
                "uid-5", BlockType.PRODUCTS_LIST, Map.of("_content_type_uid", "products_list"),
                "/blp/blocks/products-list", null, null, DynamicBlockStatus.DISABLED, "POST", LoadStrategy.LAZY,
                false, Map.of("uid", "uid-5", "source_of_data", "salesforce"));

        Map<String, Object> dto = blocksOf(mapper.toResponse(page(ph))).get(0);

        assertThat(dto).containsEntry("status", "DISABLED");
        assertThat(dto).doesNotContainKeys("fallback", "featureFlagId");
        // The contrast is the point: `fallback`/`featureFlagId` are optional metadata and are dropped
        // when null, but ms_params is a contract element and survives DISABLED — the flag can be turned
        // back on without the frontend having re-fetched the page.
        assertThat(dto).containsEntry("ms_params", Map.of("uid", "uid-5", "source_of_data", "salesforce"));
    }

    // ── eagerly resolved blocks ──────────────────────────────────────────────────────────────────

    @Test
    void toResponse_resolvedBlock_inlinesPayloadFlatAndMarksEager() {
        Object payload = Map.of("blockId", "uid-6", "title", "Ofertas", "products", List.of("a", "b"));
        ResolvedBlock resolved = new ResolvedBlock(
                "uid-6", BlockType.PRODUCTS_LIST,
                Map.of("_content_type_uid", "products_list", "products_list_title", "Ofertas config"),
                payload, "/blp/blocks/products-list", "POST", LoadStrategy.EAGER,
                DynamicBlockStatus.AVAILABLE, false,
                Map.of("uid", "uid-6", "source_of_data", "salesforce"));

        Map<String, Object> dto = blocksOf(mapper.toResponse(page(resolved))).get(0);

        assertThat(dto)
                .containsEntry("loadStrategy", "EAGER")
                .containsEntry("_content_type_uid", "products_list")
                .containsEntry("products_list_title", "Ofertas config")
                .containsEntry("title", "Ofertas")
                .containsEntry("products", List.of("a", "b"))
                .containsEntry("url", "/blp/blocks/products-list")
                .containsEntry("status", "AVAILABLE")
                // Already resolved, but the endpoint stays advertised for refresh — and a url advertised
                // without the body it requires would resolve the wrong campaign (ADR-022).
                .containsEntry("ms_params", Map.of("uid", "uid-6", "source_of_data", "salesforce"));
        // The eager payload's echoed blockId is redundant with the block's own uid and is dropped;
        // `kind` is not emitted (redundant with `loadStrategy`).
        assertThat(dto).doesNotContainKeys("blockId", "kind");
    }

    // ── root passthrough + ordering ──────────────────────────────────────────────────────────────

    @Test
    void toResponse_passesThroughTheEntryThenSectionsThenCategoryData() {
        StaticBlock block = new StaticBlock("b1", BlockType.CONTAINER, Map.of("_content_type_uid", "container"));
        StaticBlock top = new StaticBlock("t1", BlockType.BANNER_SLIDER, Map.of("_content_type_uid", "banner_slider"));
        Map<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("uid", "blt0ff08f5fb7e51522");
        entry.put("category_id", "catst26617625");
        entry.put("header", Map.of("_content_type_uid", "header"));
        LandingPage page = new LandingPage(PageType.BLP, "es-mx", entry,
                Map.of(PageSection.BLOCKS, List.of(block), PageSection.TOP_CONTENT, List.of(top)),
                category());

        Map<String, Object> root = mapper.toResponse(page).root();

        assertThat(root.keySet()).containsExactly(
                "uid", "category_id", "header", "locale", "top_content", "blocks", "categoryData");
        assertThat(root).containsEntry("locale", "es-mx");
        @SuppressWarnings("unchecked")
        Map<String, Object> categoryData = (Map<String, Object>) root.get("categoryData");
        assertThat(categoryData)
                .containsEntry("id", "CATST26617625")
                .containsEntry("breadcrumb", "home > Estee Lauder");
    }

    @Test
    void toResponse_withoutCategory_omitsCategoryData_andAbsentSectionsStayAbsent() {
        LandingPage page = new LandingPage(PageType.CLP, "es-mx", Map.of("uid", "blt1"),
                Map.of(PageSection.BOTTOM_CONTENT, List.of()), null);

        Map<String, Object> root = mapper.toResponse(page).root();

        assertThat(root).containsEntry("bottom_content", List.of());
        assertThat(root).doesNotContainKeys("categoryData", "blocks", "top_content", "template");
    }

    @Test
    void toResponse_emptyPage_returnsEmptyBlockList() {
        assertThat(blocksOf(mapper.toResponse(new LandingPage(PageType.BLP, "es-mx", Map.of(),
                Map.of(PageSection.BLOCKS, List.of()), null)))).isEmpty();
    }

    @Test
    void toResponse_preservesBlockOrderFromDomainPage() {
        StaticBlock first = new StaticBlock("first", BlockType.CONTAINER, Map.of("uid", "first"));
        StaticBlock second = new StaticBlock("second", BlockType.CONTAINER, Map.of("uid", "second"));
        StaticBlock third = new StaticBlock("third", BlockType.CONTAINER, Map.of("uid", "third"));

        List<Map<String, Object>> blocks =
                blocksOf(mapper.toResponse(new LandingPage(PageType.BLP, "es-mx", Map.of(),
                        Map.of(PageSection.BLOCKS, List.of(first, second, third)), null)));

        assertThat(blocks).extracting(block -> block.get("uid"))
                .containsExactly("first", "second", "third");
    }

    private LandingPage page(com.liverpool.ms_clp_blp.landing.models.LandingBlock block) {
        return new LandingPage(PageType.BLP, "es-mx", Map.of(), Map.of(PageSection.BLOCKS, List.of(block)), null);
    }

    private static CategoryData category() {
        CategoryMetadata metadata = new CategoryMetadata(1, null, "Estee Lauder", true,
                "/tienda/estee-lauder/catst26617625", "", "BLP", "catst26617625", "NOT_SPECIFIED", 999,
                "CATST26617625", null, null, null, null, null, true, "", false, false);
        return new CategoryData("CATST26617625", "Estee Lauder", "Estee Lauder",
                "/tienda/estee-lauder/catst26617625", false, List.of(), metadata, List.of(),
                "home > Estee Lauder", null);
    }
}
