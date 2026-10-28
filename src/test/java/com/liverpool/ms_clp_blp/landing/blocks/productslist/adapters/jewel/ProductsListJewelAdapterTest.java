package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.jewel;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.liverpool.config.properties.ApiJewelConfig;
import com.liverpool.dto.ProductRecommendation;
import com.liverpool.dto.jewel.JewelProduct;
import com.liverpool.ms_clp_blp.clients.jewel.JewelClient;
import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelModelConfig;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import feign.FeignException;
import feign.Request;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProductsListJewelAdapter}: verifies that the block's own
 * {@link JewelModelConfig} drives the request, the missing-identity behaviour, the parameters actually
 * sent, response mapping (offer_id/event_id sku derivation, promo/list price fallback, empty/null edge
 * cases), and error translation.
 *
 * <p>The {@link JewelClient} Feign interface is mocked. Where the previous revision asserted on a URI
 * query string, the equivalent assertion is now made on the arguments passed to the client — a null
 * argument is what makes Feign omit the parameter. Stub responses are deserialized from the same JSON
 * Jewel returns, keeping the wire shape under test.</p>
 */
class ProductsListJewelAdapterTest {

    private static final String BASE_URL = "http://jewel";
    private static final String BLOCK_ID = "bltjewel123";
    private static final String INTEGRATION_ID = "lp-integration-id";

    private static final SessionContext SESSION = new SessionContext(true, "LP", "WEB", "es-mx");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JewelProperties PROPS =
            new JewelProperties("x-jml-user-id", "x-jml-device-id", 3, 15, true);

    /** What the live template ships: a session-independent model with its own min/max. */
    private static final JewelModelConfig GLOBAL_MODEL =
            new JewelModelConfig("global-tps-sales", false, false, 6, 20);

    private static final JewelModelConfig NEEDS_DEVICE =
            new JewelModelConfig("recently-viewed-items-global", false, true, 6, 20);

    private static final JewelModelConfig NEEDS_USER =
            new JewelModelConfig("personalized-model", true, false, 6, 20);

    private JewelClient jewelClient;

    private ProductsListJewelAdapter adapterWith(JewelProperties properties) {
        jewelClient = mock(JewelClient.class);

        ApiJewelConfig apiJewelConfig = new ApiJewelConfig();
        apiJewelConfig.setUrl(BASE_URL);
        apiJewelConfig.setLpIntegrationId(INTEGRATION_ID);
        apiJewelConfig.setConnectTimeoutMs(3000L);
        apiJewelConfig.setReadTimeoutMs(4000L);

        return new ProductsListJewelAdapter(jewelClient, apiJewelConfig, properties);
    }

    /** A products_list query carrying Jewel provider settings — the shape the use case routes here. */
    private static ProductsListQuery query(String userId, String deviceId, JewelModelConfig config) {
        return new ProductsListQuery(
                BLOCK_ID, SESSION, new JewelSettings(userId, deviceId, config), ConditionalShowHide.NONE);
    }

    // ── the block's model config drives the request ───────────────────────────────────────────────

    @Test
    void fetch_usesBlockOwnModelConfig() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("[]");

        adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL));

        verify(jewelClient).getRecommendations(
                INTEGRATION_ID, INTEGRATION_ID, "global-tps-sales", 6, 20, null, null);
    }

    // ── skipReason: business preconditions, decided without I/O and before the breaker (ADR-029) ───

    /**
     * There is no service-level model fallback (ADR-029): every call carries the block's own CMS content
     * (ADR-024), so a block with no {@code jewel_model_config} is the legacy "nothing to resolve" case,
     * not a gap for {@code JewelProperties} to paper over.
     */
    @Test
    void skipReason_noModelConfig_isMissingModelConfig() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.skipReason(query("user-1", "device-1", null)))
                .isEqualTo(BlockDropReason.MISSING_MODEL_CONFIG);
        verifyNoInteractions(jewelClient);
    }

    @Test
    void skipReason_blankModel_isMissingModelConfig() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        JewelModelConfig noModel = new JewelModelConfig("  ", false, false, 6, 20);

        assertThat(adapter.skipReason(query("u", "d", noModel)))
                .isEqualTo(BlockDropReason.MISSING_MODEL_CONFIG);
        verifyNoInteractions(jewelClient);
    }

    @Test
    void skipReason_modelRequiresDeviceIdButMissing_isMissingDeviceIdentity() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.skipReason(query("user-1", "  ", NEEDS_DEVICE)))
                .isEqualTo(BlockDropReason.MISSING_DEVICE_IDENTITY);
        verifyNoInteractions(jewelClient);
    }

    @Test
    void skipReason_modelRequiresUserIdButMissing_isMissingUserIdentity() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.skipReason(query(null, "device-1", NEEDS_USER)))
                .isEqualTo(BlockDropReason.MISSING_USER_IDENTITY);
        verifyNoInteractions(jewelClient);
    }

    @Test
    void skipReason_modelWithItsIdentitySatisfied_isNull() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.skipReason(query("user-1", "device-1", NEEDS_USER))).isNull();
        assertThat(adapter.skipReason(query("user-1", "device-1", GLOBAL_MODEL))).isNull();
    }

    // ── cacheability ──────────────────────────────────────────────────────────────────────────────

    @Test
    void sessionIndependent_globalModelNeedingNoIdentity_isTrue() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.sessionIndependent(query("u", "d", GLOBAL_MODEL))).isTrue();
    }

    @Test
    void sessionIndependent_modelNeedingUserOrDevice_isFalse() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.sessionIndependent(query("u", "d", NEEDS_USER))).isFalse();
        assertThat(adapter.sessionIndependent(query("u", "d", NEEDS_DEVICE))).isFalse();
    }

    /** No model config at all never calls Jewel, so it carries nothing session-specific either. */
    @Test
    void sessionIndependent_noModelConfig_isTrue() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);

        assertThat(adapter.sessionIndependent(query("u", "d", null))).isTrue();
    }

    @Test
    void source_isJewel() {
        assertThat(adapterWith(PROPS).source())
                .isEqualTo(com.liverpool.ms_clp_blp.landing.models.SourceOfData.JEWEL);
    }

    // ── request parameters ────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_sendsExpectedParameters() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        JewelModelConfig personalized = new JewelModelConfig("home-carousel", true, true, 3, 15);
        stub("[]");

        adapter.fetch(query("user-1", "device-1", personalized));

        verify(jewelClient).getRecommendations(
                INTEGRATION_ID, INTEGRATION_ID, "home-carousel", 3, 15, "user-1", "device-1");
    }

    /**
     * A model that does not declare an identity requirement must not receive one, even when the
     * request carries it — otherwise a tracked visitor gets personalised results from a model meant
     * to return the same global list to everyone (Rule 19 reference: {@code jewel.provider.ts}).
     * Passing {@code null} is what makes Feign leave the query parameter off entirely.
     */
    @Test
    void fetch_modelDoesNotRequireIdentity_omitsIdentityParamsEvenWhenPresent() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("[]");

        adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL));

        assertThat(capturedUserId()).isNull();
        assertThat(capturedDeviceId()).isNull();
    }

    @Test
    void fetch_noUserOrDeviceId_omitsThoseParams() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("[]");

        adapter.fetch(query(null, null, GLOBAL_MODEL));

        assertThat(capturedUserId()).isNull();
        assertThat(capturedDeviceId()).isNull();
    }

    /** A blank id must become null, or Feign would send an empty {@code user_id=} parameter. */
    @Test
    void fetch_blankIdentity_isSentAsNullNotEmptyString() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        JewelModelConfig personalized = new JewelModelConfig("home-carousel", true, true, 3, 15);
        stub("[]");

        adapter.fetch(query("  ", "  ", personalized));

        assertThat(capturedUserId()).isNull();
        assertThat(capturedDeviceId()).isNull();
    }

    // ── response mapping (field-level detail lives in JewelProductMapperTest) ─────────────────────

    @Test
    void fetch_mapsProductsIntoThePublishedContract() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("""
                [{
                  "title": "Recommended Product",
                  "standard_features": {
                    "offer_id": "SKU123_XL_RED",
                    "min_promo_price": 149.5,
                    "min_list_price": 199.0,
                    "sale_price": 149.5,
                    "brand": "AMERICAN EAGLE"
                  }
                }]
                """);

        ProductsListResolution result = adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.products()).hasSize(1);
        ProductRecommendation product = result.products().get(0);
        assertThat(product.getProductId()).isEqualTo("SKU123");
        assertThat(product.getName()).isEqualTo("Recommended Product");
        assertThat(product.getBrand()).isEqualTo("AMERICAN EAGLE");
        assertThat(product.getPriceInfo().getPrice()).isEqualTo(149.5);
    }

    @Test
    void fetch_productWithoutAnyId_isDropped() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("""
                [
                  { "title": "No standard features" },
                  { "title": "Has id", "standard_features": { "offer_id": "SKU-1" } }
                ]
                """);

        ProductsListResolution result = adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL));

        assertThat(result.products()).hasSize(1);
        assertThat(result.products().get(0).getProductId()).isEqualTo("SKU-1");
    }

    @Test
    void fetch_emptyArray_returnsEmptyResolution() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        stub("[]");

        ProductsListResolution result = adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL));

        assertThat(result.blockId()).isEqualTo(BLOCK_ID);
        assertThat(result.products()).isEmpty();
    }

    /** The variants flag is a service setting, so the adapter is where it must take effect. */
    @Test
    void fetch_variantsFlagOff_publishesNoVariants() {
        JewelProperties noVariants =
                new JewelProperties("x-jml-user-id", "x-jml-device-id", 3, 15, false);
        ProductsListJewelAdapter adapter = adapterWith(noVariants);
        stub("""
                [{ "title": "P", "standard_features": {
                     "offer_id": "SKU-1",
                     "item_variants": [ { "active": true, "title": "V" } ] } }]
                """);

        ProductsListResolution result = adapter.fetch(query("u", "d", GLOBAL_MODEL));

        assertThat((java.util.List<?>) result.products().get(0).getVariants()).isEmpty();
    }

    // ── error handling ────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_jewelReturns500_throwsDynamicBlockServiceUnavailableException() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        when(jewelClient.getRecommendations(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new FeignException.InternalServerError("boom", dummyRequest(), null, Map.of()));

        assertThatThrownBy(() -> adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    // ADR-029: jewel_product_recommendation was never a real content type.
                    assertThat(e.getBlockType()).isEqualTo("products_list");
                });
    }

    /** Connect/read timeouts arrive as RetryableException, a FeignException subclass. */
    @Test
    void fetch_transportFailure_throwsDynamicBlockServiceUnavailableException() {
        ProductsListJewelAdapter adapter = adapterWith(PROPS);
        when(jewelClient.getRecommendations(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new feign.RetryableException(
                        -1, "read timed out", Request.HttpMethod.GET, (Long) null, dummyRequest()));

        assertThatThrownBy(() -> adapter.fetch(query("user-1", "device-1", GLOBAL_MODEL)))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────

    private void stub(String json) {
        List<JewelProduct> products = Arrays.asList(MAPPER.readValue(json, JewelProduct[].class));
        when(jewelClient.getRecommendations(anyString(), anyString(), anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(products);
    }

    private String capturedUserId() {
        return captureIdentities()[0];
    }

    private String capturedDeviceId() {
        return captureIdentities()[1];
    }

    private String[] captureIdentities() {
        ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> deviceId = ArgumentCaptor.forClass(String.class);
        verify(jewelClient).getRecommendations(
                anyString(), anyString(), anyString(), anyInt(), anyInt(), userId.capture(), deviceId.capture());
        return new String[] { userId.getValue(), deviceId.getValue() };
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.GET, BASE_URL + "/c/p/" + INTEGRATION_ID + "/l",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
