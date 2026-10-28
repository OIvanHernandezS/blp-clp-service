package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.salesforcev2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;

import com.liverpool.dto.salesforce.v2.SalesforceV2Product;
import com.liverpool.dto.salesforce.v2.SalesforceV2Request;
import com.liverpool.dto.salesforce.v2.SalesforceV2Response;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Client;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;
import com.liverpool.ms_clp_blp.landing.models.SourceOfData;
import com.liverpool.ms_clp_blp.shared.errors.DynamicBlockServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;

import feign.FeignException;
import feign.Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductsListSalesforceV2AdapterTest {

    private static final String BLOCK_ID = "bltsfv2";
    private static final String MODEL = "home-top-sellers";
    private static final SessionContext SESSION = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SalesforceV2Properties PROPS =
            new SalesforceV2Properties("x-sf-user-id", "x-sf-device-id");

    private SalesforceV2Client client;
    private ProductsListSalesforceV2Adapter adapter;

    @BeforeEach
    void setUp() {
        client = mock(SalesforceV2Client.class);
        adapter = new ProductsListSalesforceV2Adapter(client, PROPS);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void source_isSalesforceV2() {
        assertThat(adapter.source()).isEqualTo(SourceOfData.SALESFORCE_V2);
    }

    @Test
    void sessionIndependent_isAlwaysFalse() {
        assertThat(adapter.sessionIndependent(query(settings(MODEL, false, false, false, null, null)))).isFalse();
    }

    // ── skip ladder ──────────────────────────────────────────────────────────────────────────────

    @Test
    void skipReason_noModel_isMissingModelConfig() {
        assertThat(adapter.skipReason(query(settings(null, false, false, false, "u", "d"))))
                .isEqualTo(BlockDropReason.MISSING_MODEL_CONFIG);
        assertThat(adapter.skipReason(query(settings("  ", false, false, false, "u", "d"))))
                .isEqualTo(BlockDropReason.MISSING_MODEL_CONFIG);
    }

    /** Home has no product context, so a point that needs an anchor can never be satisfied here. */
    @Test
    void skipReason_requiresItemId_isMissingAnchorProduct() {
        assertThat(adapter.skipReason(query(settings(MODEL, false, false, true, "u", "d"))))
                .isEqualTo(BlockDropReason.MISSING_ANCHOR_PRODUCT);
    }

    @Test
    void skipReason_requiresUserIdWithoutOne_isMissingUserIdentity() {
        assertThat(adapter.skipReason(query(settings(MODEL, true, false, false, " ", "d"))))
                .isEqualTo(BlockDropReason.MISSING_USER_IDENTITY);
    }

    @Test
    void skipReason_requiresDeviceIdWithoutOne_isMissingDeviceIdentity() {
        assertThat(adapter.skipReason(query(settings(MODEL, false, true, false, "u", null))))
                .isEqualTo(BlockDropReason.MISSING_DEVICE_IDENTITY);
    }

    @Test
    void skipReason_requirementsMet_isNull() {
        assertThat(adapter.skipReason(query(settings(MODEL, true, true, false, "u", "d")))).isNull();
    }

    /** No requirement authored: the point is asked even for an anonymous request, as commons does. */
    @Test
    void skipReason_noRequirementsAndNoIdentity_isNull() {
        assertThat(adapter.skipReason(query(settings(MODEL, false, false, false, null, null)))).isNull();
    }

    // ── request ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_sendsOnePointWithTheUserIdAndTheTraceId() {
        MDC.put("traceId", "trace-1");
        stub(response(personalization(MODEL, "pers-1", product("A"))));

        adapter.fetch(query(settings(MODEL, false, false, false, "user-1", "dev-1")));

        SalesforceV2Request request = capturedRequest();
        assertThat(request.personalizationPoints()).extracting(SalesforceV2Request.PersonalizationPoint::name)
                .containsExactly(MODEL);
        assertThat(request.context().individualId()).isEqualTo("user-1");
        assertThat(request.context().anchorId()).isNull();
        assertThat(request.context().correlationId()).isEqualTo("trace-1");
    }

    @Test
    void fetch_withoutUserId_sendsTheDeviceIdAsIndividual() {
        stub(response(personalization(MODEL, "pers-1", product("A"))));

        adapter.fetch(query(settings(MODEL, false, true, false, null, "dev-1")));

        assertThat(capturedRequest().context().individualId()).isEqualTo("dev-1");
    }

    // ── response ─────────────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_matchingPersonalization_isAvailableWithItsProductsAndId() {
        stub(response(
                personalization("other-point", "pers-x", product("X")),
                personalization(MODEL, "pers-1", product("A"), product("B"))));

        ProductsListResolution result = adapter.fetch(query(settings(MODEL, false, false, false, "u", null)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
        assertThat(result.products()).extracting(p -> p.getProductId()).containsExactly("A", "B");
        assertThat(result.products()).allSatisfy(p -> assertThat(p.getPersonalization().getId()).isEqualTo("pers-1"));
    }

    /** Salesforce may leave the point unnamed; with a single point requested it is ours. */
    @Test
    void fetch_unnamedPersonalization_isAccepted() {
        stub(response(personalization(null, "pers-1", product("A"))));

        ProductsListResolution result = adapter.fetch(query(settings(MODEL, false, false, false, "u", null)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.AVAILABLE);
    }

    @Test
    void fetch_noMatchingPersonalization_isEmptyWithNoProducts() {
        stub(response(personalization("other-point", "pers-x", product("X"))));

        ProductsListResolution result = adapter.fetch(query(settings(MODEL, false, false, false, "u", null)));

        assertThat(result.status()).isEqualTo(DynamicBlockStatus.EMPTY);
        assertThat(result.reason()).isEqualTo(BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
    }

    @Test
    void fetch_nullBodyOrNoPersonalizations_isEmpty() {
        when(client.getDecisions(any())).thenReturn(ResponseEntity.ok().build());
        assertThat(adapter.fetch(query(settings(MODEL, false, false, false, "u", null))).reason())
                .isEqualTo(BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);

        stub(new SalesforceV2Response());
        assertThat(adapter.fetch(query(settings(MODEL, false, false, false, "u", null))).reason())
                .isEqualTo(BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
    }

    @Test
    void fetch_personalizationWithOnlyUnidentifiableProducts_isEmpty() {
        stub(response(personalization(MODEL, "pers-1", new SalesforceV2Product())));

        assertThat(adapter.fetch(query(settings(MODEL, false, false, false, "u", null))).reason())
                .isEqualTo(BlockDropReason.UPSTREAM_RETURNED_NO_PRODUCTS);
    }

    // ── errors ───────────────────────────────────────────────────────────────────────────────────

    /** A failure must surface, not be swallowed into EMPTY as commons' fallback does. */
    @Test
    void fetch_salesforceReturns500_throwsDynamicBlockServiceUnavailableException() {
        when(client.getDecisions(any()))
                .thenThrow(new FeignException.InternalServerError("boom", dummyRequest(), null, Map.of()));

        assertThatThrownBy(() -> adapter.fetch(query(settings(MODEL, false, false, false, "u", null))))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class)
                .satisfies(ex -> {
                    DynamicBlockServiceUnavailableException e = (DynamicBlockServiceUnavailableException) ex;
                    assertThat(e.getBlockId()).isEqualTo(BLOCK_ID);
                    assertThat(e.getBlockType()).isEqualTo("products_list");
                    assertThat(e.getUpstream()).isEqualTo("salesforce_v2");
                    assertThat(e.getUpstreamStatus()).isEqualTo(500);
                });
    }

    @Test
    void fetch_transportFailure_throwsDynamicBlockServiceUnavailableException() {
        when(client.getDecisions(any())).thenThrow(new feign.RetryableException(
                -1, "read timed out", Request.HttpMethod.POST, (Long) null, dummyRequest()));

        assertThatThrownBy(() -> adapter.fetch(query(settings(MODEL, false, false, false, "u", null))))
                .isInstanceOf(DynamicBlockServiceUnavailableException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private static SalesforceV2Settings settings(String model, boolean requiresUser, boolean requiresDevice,
                                                 boolean requiresItem, String userId, String deviceId) {
        return new SalesforceV2Settings(userId, deviceId, model, requiresUser, requiresDevice, requiresItem,
                null, null);
    }

    private static ProductsListQuery query(SalesforceV2Settings settings) {
        return new ProductsListQuery(BLOCK_ID, SESSION, settings, ConditionalShowHide.NONE);
    }

    private void stub(SalesforceV2Response response) {
        when(client.getDecisions(any())).thenReturn(ResponseEntity.ok(response));
    }

    private SalesforceV2Request capturedRequest() {
        ArgumentCaptor<SalesforceV2Request> captor = ArgumentCaptor.forClass(SalesforceV2Request.class);
        verify(client).getDecisions(captor.capture());
        return captor.getValue();
    }

    private static SalesforceV2Response response(SalesforceV2Response.Personalization... personalizations) {
        SalesforceV2Response response = new SalesforceV2Response();
        response.setPersonalizations(new ArrayList<>(List.of(personalizations)));
        return response;
    }

    private static SalesforceV2Response.Personalization personalization(String pointName, String id,
                                                                        SalesforceV2Product... products) {
        SalesforceV2Response.Personalization personalization = new SalesforceV2Response.Personalization();
        personalization.setPersonalizationPointName(pointName);
        personalization.setPersonalizationId(id);
        personalization.setData(new ArrayList<>(List.of(products)));
        return personalization;
    }

    private static SalesforceV2Product product(String masterProductId) {
        SalesforceV2Product product = new SalesforceV2Product();
        product.setMasterProductId(masterProductId);
        return product;
    }

    private static Request dummyRequest() {
        return Request.create(Request.HttpMethod.POST, "http://salesforce-v2/personalization/decisions",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
