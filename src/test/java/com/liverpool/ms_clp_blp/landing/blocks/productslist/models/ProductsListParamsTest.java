package com.liverpool.ms_clp_blp.landing.blocks.productslist.models;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection behind every {@code products_list} block's advertised {@code ms_params}.
 *
 * <p>The tests that matter most are the <strong>round-trip</strong> ones: they prove that the endpoint's
 * readers extract identical settings from the projection and from the full block, which is the entire
 * premise of the change — {@code ProductsListControllerV1} needs no new parsing path, and a frontend
 * still posting whole blocks behaves exactly like one posting only {@code ms_params}. If someone adds a
 * field to a {@code from(...)} reader and forgets the matching {@code paramsFrom(...)}, these fail.</p>
 */
class ProductsListParamsTest {

    private static final int JEWEL_FALLBACK_MIN = 4;
    private static final int JEWEL_FALLBACK_MAX = 12;

    // ── round-trip equivalence: the projection parses exactly like the full block ─────────────────

    @Test
    void salesforceSettings_readTheSameFromTheProjectionAsFromTheFullBlock() {
        Map<String, Object> full = salesforceBlock();

        Map<String, Object> params = ProductsListParams.of("blt-sf", full);

        assertThat(SalesforceSettings.from(params, "profile-1"))
                .isEqualTo(SalesforceSettings.from(full, "profile-1"));
    }

    /**
     * Legacy entries predate {@code source_of_data} and keep their historical Salesforce routing, so the
     * absent-source case must project through the Salesforce reader too.
     */
    @Test
    void legacyBlockWithoutSourceOfData_readsTheSameAsTheFullBlock() {
        Map<String, Object> full = salesforceBlock();
        full.remove("source_of_data");

        Map<String, Object> params = ProductsListParams.of("blt-legacy", full);

        assertThat(SalesforceSettings.from(params, "profile-1"))
                .isEqualTo(SalesforceSettings.from(full, "profile-1"));
        assertThat(params).doesNotContainKey("source_of_data");
    }

    @Test
    void jewelModelConfig_readsTheSameFromTheProjectionAsFromTheFullBlock() {
        Map<String, Object> full = jewelBlock();

        Map<String, Object> params = ProductsListParams.of("blt-jewel", full);

        assertThat(JewelModelConfig.from(params, JEWEL_FALLBACK_MIN, JEWEL_FALLBACK_MAX))
                .isEqualTo(JewelModelConfig.from(full, JEWEL_FALLBACK_MIN, JEWEL_FALLBACK_MAX));
    }

    @Test
    void vertexSettings_readTheSameFromTheProjectionAsFromTheFullBlock() {
        Map<String, Object> full = vertexBlock();

        Map<String, Object> params = ProductsListParams.of("blt-vertex", full);

        assertThat(VertexSettings.from(params)).isEqualTo(VertexSettings.from(full));
    }

    @Test
    void salesforceV2Settings_readTheSameFromTheProjectionAsFromTheFullBlock() {
        Map<String, Object> full = salesforceV2Block();

        Map<String, Object> params = ProductsListParams.of("blt-sf2", full);

        assertThat(SalesforceV2Settings.from(params, "user-1", "dev-1"))
                .isEqualTo(SalesforceV2Settings.from(full, "user-1", "dev-1"));
    }

    @Test
    void salesforceV2Block_projectsExactlyTheKeysTheEndpointReads() {
        Map<String, Object> params = ProductsListParams.of("blt-sf2", salesforceV2Block());

        assertThat(params).containsOnlyKeys(
                "uid", "source_of_data", "salesforce_v2_config", "min_of_products", "max_of_products");
        assertThat(params.get("salesforce_v2_config")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("model", "requires_user_id", "requires_device_id", "requires_item_id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jewel", "salesforce", "salesforce_v2", "vertex"})
    void routingSourceSurvivesTheProjection(String source) {
        Map<String, Object> full = new LinkedHashMap<>(Map.of("source_of_data", source));

        Map<String, Object> params = ProductsListParams.of("blt-1", full);

        assertThat(SourceOfData.from(params.get("source_of_data")))
                .isEqualTo(SourceOfData.from(full.get("source_of_data")));
    }

    /**
     * Absent optional settings must stay absent so the endpoint applies the same service-level fallback
     * it applies today — projecting an explicit null would change that.
     */
    @Test
    void absentJewelThresholds_fallBackIdenticallyThroughTheProjection() {
        Map<String, Object> full = jewelBlock();
        full.remove("min_of_products");
        full.remove("max_of_products");

        Map<String, Object> params = ProductsListParams.of("blt-jewel", full);

        assertThat(params).doesNotContainKeys("min_of_products", "max_of_products");
        assertThat(JewelModelConfig.from(params, JEWEL_FALLBACK_MIN, JEWEL_FALLBACK_MAX))
                .isEqualTo(JewelModelConfig.from(full, JEWEL_FALLBACK_MIN, JEWEL_FALLBACK_MAX));
    }

    // ── the projection itself ─────────────────────────────────────────────────────────────────────

    @Test
    void jewelBlock_projectsExactlyTheFiveKeysTheEndpointReads() {
        Map<String, Object> params = ProductsListParams.of("blt-jewel", jewelBlock());

        assertThat(params).containsOnlyKeys(
                "uid", "source_of_data", "jewel_model_config", "min_of_products", "max_of_products");
        assertThat(params).containsEntry("uid", "blt-jewel");
        assertThat(params.get("jewel_model_config")).asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("jewel_model", "jewel_requires_user_id", "jewel_requires_device_id");
    }

    @Test
    void salesforceBlock_projectsExactlyTheKeysTheEndpointReads() {
        Map<String, Object> params = ProductsListParams.of("blt-sf", salesforceBlock());

        assertThat(params).containsOnlyKeys(
                "uid", "source_of_data", "salesforce_carousel", "salesforce_category_id",
                "min_of_products", "max_of_products");
        // Copied verbatim, so a body carrying only ms_params parses exactly like the full block.
        assertThat(params).containsEntry("salesforce_category_id", "cat-4020");
    }

    // ── audience_filter ──────────────────────────────────────────────────────────────────────────

    /**
     * The live page ships guest and logged variants of the same campaign, so the audience the block was
     * authored for is what tells two otherwise-identical carousels apart in a resolution request.
     */
    @ParameterizedTest
    @ValueSource(strings = {"guest", "logged"})
    void audienceFilter_isProjectedWhenTheBlockCarriesIt(String audience) {
        Map<String, Object> full = jewelBlock();
        full.put("audience_filter", audience);

        Map<String, Object> params = ProductsListParams.of("blt-jewel", full);

        assertThat(params).containsEntry("audience_filter", audience);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jewel", "salesforce", "salesforce_v2", "vertex"})
    void audienceFilter_isProjectedForEveryProvider(String source) {
        Map<String, Object> full = new LinkedHashMap<>(
                Map.of("source_of_data", source, "audience_filter", "guest"));

        assertThat(ProductsListParams.of("blt-1", full)).containsEntry("audience_filter", "guest");
    }

    /** A block authored for every audience omits the field; projecting it would invent a target. */
    @Test
    void absentAudienceFilter_isOmittedRatherThanProjectedAsNull() {
        Map<String, Object> params = ProductsListParams.of("blt-jewel", jewelBlock());

        assertThat(params).doesNotContainKey("audience_filter");
    }

    /**
     * The production fixture carries two {@code products_list} blocks with an explicit
     * {@code "audience_filter": null}. An explicit null states no target either, so it is treated like
     * an absent key rather than projected as a null the endpoint would have to defend against.
     */
    @Test
    void explicitNullAudienceFilter_isTreatedAsAbsent() {
        Map<String, Object> full = jewelBlock();
        full.put("audience_filter", null);

        assertThat(ProductsListParams.of("blt-jewel", full)).doesNotContainKey("audience_filter");
    }

    /**
     * {@code products_data} is the one nested case. Only {@code category} is ever read, so the sibling
     * keys an author adds ({@code brandId}, …) are dropped while the wrapper stays, because that is how
     * {@link VertexSettings#from} looks the value up.
     */
    @Test
    void vertexBlock_keepsProductsDataNestingButOnlyTheCategory() {
        Map<String, Object> params = ProductsListParams.of("blt-vertex", vertexBlock());

        assertThat(params).containsOnlyKeys(
                "uid", "source_of_data", "products_data", "min_of_products", "max_of_products");
        assertThat(params).containsEntry("products_data", Map.of("category", "cat-123"));
    }

    /**
     * The CMS authors numeric fields as strings and the endpoint's readers parse them leniently. Coercing
     * here would give the projected body a different shape from the full one — the exact drift this
     * change exists to avoid.
     */
    @Test
    void valuesAreCopiedVerbatimWithoutCoercion() {
        Map<String, Object> params = ProductsListParams.of("blt-sf", salesforceBlock());

        assertThat(params).containsEntry("min_of_products", "6");
        assertThat(params).containsEntry("max_of_products", "20");
    }

    @Test
    void aFullyPopulatedCmsBlock_collapsesToAHandfulOfKeys() {
        Map<String, Object> full = jewelBlock();
        full.put("audience_filter", "guest");
        full.putAll(Map.of(
                "events", List.of(Map.of("name", "select_item"), Map.of("name", "view_item_list")),
                "container_background", Map.of("color", Map.of(), "image", Map.of()),
                "spacing", Map.of("padding_top", "none", "padding_bottom", "none"),
                "publish_details", Map.of("time", "2026-05-21T15:01:46.884Z"),
                "ACL", Map.of(),
                "link", Map.of("label", "", "url", ""),
                "title", "4 - Jewel | Guest | Productos en tendencia",
                "products_list_title", "Productos en tendencia",
                "variant", "slider",
                "handle", "bff"));

        Map<String, Object> params = ProductsListParams.of("blt-jewel", full);

        assertThat(full).hasSize(15);
        assertThat(params).hasSize(6);
        assertThat(params).doesNotContainKeys("events", "container_background", "spacing",
                "publish_details", "ACL", "link", "title", "products_list_title", "variant", "handle");
    }

    // ── conditional_show_hide ────────────────────────────────────────────────────────────────────

    /**
     * With the flag on, a block carrying an active rule is never inlined into {@code GET /blp|clp/**}, so the
     * resolution request built from {@code ms_params} is the only place the rule can be honoured.
     */
    @ParameterizedTest
    @ValueSource(strings = {"jewel", "salesforce", "salesforce_v2", "vertex"})
    void conditionalShowHide_readsTheSameFromTheProjectionForEveryProvider(String source) {
        Map<String, Object> full = new LinkedHashMap<>(Map.of("source_of_data", source));
        full.put("conditional_show_hide", abGroup());

        Map<String, Object> params = ProductsListParams.of("blt-ab", full);

        assertThat(ConditionalShowHide.from(params)).isEqualTo(ConditionalShowHide.from(full));
        assertThat(ConditionalShowHide.from(params).applies()).isTrue();
    }

    @Test
    void conditionalShowHide_isCopiedVerbatim() {
        Map<String, Object> full = jewelBlock();
        Map<String, Object> group = abGroup();
        full.put("conditional_show_hide", group);

        assertThat(ProductsListParams.of("blt-ab", full)).containsEntry("conditional_show_hide", group);
    }

    @Test
    void absentConditionalShowHide_isOmitted() {
        assertThat(ProductsListParams.of("blt-jewel", jewelBlock())).doesNotContainKey("conditional_show_hide");
    }

    // ── degenerate input ─────────────────────────────────────────────────────────────────────────

    @Test
    void unsupportedSource_projectsOnlyTheRoutingKeys() {
        Map<String, Object> full = new LinkedHashMap<>(Map.of(
                "source_of_data", "groupby", "salesforce_carousel", "CMSTePuedeGustar"));

        Map<String, Object> params = ProductsListParams.of("blt-x", full);

        assertThat(params).containsOnlyKeys("uid", "source_of_data");
    }

    @Test
    void nullAttributes_yieldOnlyTheBlockId() {
        assertThat(ProductsListParams.of("blt-x", null)).containsExactlyEntriesOf(Map.of("uid", "blt-x"));
    }

    @Test
    void nullBlockId_isOmittedRatherThanProjectedAsNull() {
        assertThat(ProductsListParams.of(null, salesforceBlock())).doesNotContainKey("uid");
    }

    /**
     * A Jewel block with no {@code jewel_model_config} is unresolvable, and {@code from} reports that by
     * returning null. The projection must not invent an empty config that would hide it.
     */
    @Test
    void jewelBlockWithoutModelConfig_projectsNoModelConfig() {
        Map<String, Object> full = new LinkedHashMap<>(Map.of("source_of_data", "jewel"));

        Map<String, Object> params = ProductsListParams.of("blt-jewel", full);

        assertThat(params).doesNotContainKey("jewel_model_config");
        assertThat(JewelModelConfig.from(params, JEWEL_FALLBACK_MIN, JEWEL_FALLBACK_MAX)).isNull();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private static Map<String, Object> jewelBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source_of_data", "jewel");
        block.put("min_of_products", "6");
        block.put("max_of_products", "20");
        block.put("jewel_model_config", new LinkedHashMap<>(Map.of(
                "jewel_model", "global-tps-sales",
                "jewel_requires_user_id", false,
                "jewel_requires_device_id", false)));
        return block;
    }

    private static Map<String, Object> salesforceBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source_of_data", "salesforce");
        block.put("salesforce_carousel", "CMSOfertasIncreibles");
        block.put("salesforce_category_id", "cat-4020");
        block.put("min_of_products", "6");
        block.put("max_of_products", "20");
        return block;
    }

    private static Map<String, Object> salesforceV2Block() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source_of_data", "salesforce_v2");
        block.put("min_of_products", "4");
        block.put("max_of_products", "12");
        block.put("salesforce_v2_config", new LinkedHashMap<>(Map.of(
                "model", "home-top-sellers",
                "requires_user_id", false,
                "requires_device_id", true,
                "requires_item_id", false,
                "unread_extra", "ignored")));
        block.put("salesforce_carousel", "CMSOfertasIncreibles");
        return block;
    }

    private static Map<String, Object> abGroup() {
        return new LinkedHashMap<>(Map.of(
                "active", true,
                "action", "show",
                "header_name", "x-ab-carousel",
                "cookie_name", "ab_carousel"));
    }

    private static Map<String, Object> vertexBlock() {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("source_of_data", "vertex");
        block.put("min_of_products", "6");
        block.put("max_of_products", "20");
        block.put("products_data", new LinkedHashMap<>(Map.of("category", "cat-123", "brandId", "LP")));
        return block;
    }
}
