package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.dto.ProductRecommendation;
import com.liverpool.ms_clp_blp.clients.jewel.JewelProperties;
import com.liverpool.ms_clp_blp.clients.salesforce.SalesforceProperties;
import com.liverpool.ms_clp_blp.clients.salesforcev2.SalesforceV2Properties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ProductsListProperties;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.JewelSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListQuery;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.ProductsListResolution;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.SalesforceV2Settings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.models.VertexSettings;
import com.liverpool.ms_clp_blp.landing.blocks.productslist.ports.ProductsListService;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.ConditionalShowHide;
import com.liverpool.ms_clp_blp.shared.errors.ServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.ProductJson;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link ProductsListControllerV1} — loads only the web layer.
 *
 * <p>The endpoint is batch-first (ADR-029): it always takes an array of block objects and always answers
 * with an array, resolving each block's provider from its own {@code source_of_data}.</p>
 */
@WebMvcTest(ProductsListControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class, ProductJson.class})
@WithServiceCredential
class ProductsListControllerV1Test {

    private static final String PATH = "/blp/blocks/products-list";
    private static final String SF_BLOCK = "bltsalesforce001";
    private static final String JW_BLOCK = "bltjewel002";
    private static final String USER_ID = "31005309570";
    private static final String SF_USER_ID_HEADER = "x-sf-user-id";
    private static final String SF_DEVICE_ID_HEADER = "x-sf-device-id";
    private static final String SF2_BLOCK = "bltsalesforcev2003";
    private static final String SESSION_ID = "f2207b1b-a5f4-47cd-b70a-fcf4072c2d04.c2ln";

    /**
     * Every fixture here carries {@code authenticated=false} and {@code profileId=null} because this
     * endpoint reads its context from {@code currentContextWithoutIdentity()} (ADR-054), which never
     * performs the token exchange and so can never report a logged-in caller. What discriminates the
     * cases below is the pair ADR-041 actually gates on: the presence of a well-formed {@code SessionId}
     * cookie (checked locally, no call) and the {@code x-sf-user-id} header that carries the profile id.
     */
    /** ADR-041's supported shape: a SessionId cookie travelled AND x-sf-user-id carries the id. */
    private static final SessionContext SESSION_AND_SF_HEADER = new SessionContext(
            false, "LP", "WEB", "es-mx", null,
            Map.of("x-jml-user-id", "jml-1", "x-jml-device-id", "dev-1",
                    SF_USER_ID_HEADER, USER_ID),
            SESSION_ID);
    /** Neither condition met — no cookie, no header. */
    private static final SessionContext NO_SESSION_NO_HEADER =
            new SessionContext(false, "LP", "WEB", "es-mx");
    /** Header present, no SessionId cookie — Salesforce V1 only serves logged-in shoppers. */
    private static final SessionContext NO_SESSION_ID = new SessionContext(
            false, "LP", "WEB", "es-mx", null, Map.of(SF_USER_ID_HEADER, USER_ID), null);
    /** SessionId present, header absent — there is no profile id to personalise on. */
    private static final SessionContext NO_SF_HEADER = new SessionContext(
            false, "LP", "WEB", "es-mx", null, Map.of(), SESSION_ID);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductsListService productsListService;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private ProductsListMapper productsListMapper;

    @MockitoBean
    private JewelProperties jewelProperties;

    @MockitoBean
    private SalesforceProperties salesforceProperties;

    @MockitoBean
    private SalesforceV2Properties salesforceV2Properties;

    @MockitoBean
    private ProductsListProperties productsListProperties;

    @BeforeEach
    void setUp() {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(SESSION_AND_SF_HEADER);
        when(salesforceProperties.userIdHeader()).thenReturn(SF_USER_ID_HEADER);
        when(salesforceV2Properties.userIdHeader()).thenReturn(SF_USER_ID_HEADER);
        when(salesforceV2Properties.deviceIdHeader()).thenReturn(SF_DEVICE_ID_HEADER);
        when(jewelProperties.userIdHeader()).thenReturn("x-jml-user-id");
        when(jewelProperties.deviceIdHeader()).thenReturn("x-jml-device-id");
        when(jewelProperties.minItems()).thenReturn(3);
        when(jewelProperties.maxItems()).thenReturn(15);
        when(productsListProperties.cacheSeconds()).thenReturn(300);
    }

    // ── batch resolution ─────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_singleSalesforceBlock_returns200WithOneEntry() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "Best Sellers")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.blocks.length()").value(1))
                .andExpect(jsonPath("$.blocks[0].blockId").value(SF_BLOCK))
                .andExpect(jsonPath("$.blocks[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.blocks[0].content.products_list_title").value("Best Sellers"));
    }

    @Test
    void resolve_mixedProviders_preservesRequestOrder() throws Exception {
        stubBatch(List.of(available(JW_BLOCK, null), available(SF_BLOCK, "Best Sellers")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(jewelBlock(JW_BLOCK), salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(2))
                .andExpect(jsonPath("$.blocks[0].blockId").value(JW_BLOCK))
                .andExpect(jsonPath("$.blocks[1].blockId").value(SF_BLOCK));
    }

    /** One provider failing must not fail the batch — the page still renders the rest. */
    @Test
    void resolve_oneBlockUnavailable_stillReturns200WithPerBlockStatus() throws Exception {
        stubBatch(List.of(
                ProductsListResolution.unavailable(SF_BLOCK, BlockDropReason.UPSTREAM_FAILED),
                available(JW_BLOCK, null)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK), jewelBlock(JW_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.blocks[0].reason").value("UPSTREAM_FAILED"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist())
                .andExpect(jsonPath("$.blocks[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.blocks[1].content").exists());
    }

    /**
     * A carousel that resolved to nothing is {@code EMPTY} and names the rule, so the frontend drops the
     * block instead of retrying it.
     *
     * <p>This is the reported bug, pinned: the entry used to come back {@code AVAILABLE} with no
     * {@code content}, which the app read as a transient failure and retried forever. The
     * {@code BELOW_MIN_PRODUCTS} asserted here is the same value the service logs on the
     * {@code BLOCK_DROPPED} line for the same request (ADR-043).</p>
     */
    @Test
    void resolve_emptyCarousel_isEmptyWithTheReasonAndNoContent() throws Exception {
        when(productsListService.resolveAll(anyList()))
                .thenReturn(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.BELOW_MIN_PRODUCTS)));
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.blocks[0].reason").value("BELOW_MIN_PRODUCTS"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist());
    }

    /**
     * The shape a switched-off provider puts on the wire. Pinned here because the JSON is the
     * deliverable, and because {@code DISABLED} would be the intuitive status but is forbidden on this
     * contract — a resolution response is only ever {@code AVAILABLE}, {@code EMPTY} or
     * {@code UNAVAILABLE} (see {@code DynamicBlockStatus}).
     */
    @Test
    void resolve_providerSwitchedOff_isEmptyWithFeatureFlagOff() throws Exception {
        when(productsListService.resolveAll(anyList()))
                .thenReturn(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.FEATURE_FLAG_OFF)));
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.blocks[0].reason").value("FEATURE_FLAG_OFF"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist());
    }

    /** A resolved carousel carries no reason at all — the key is absent, not null. */
    @Test
    void resolve_resolvedCarousel_omitsTheReason() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "Best Sellers")));
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.blocks[0].reason").doesNotExist())
                .andExpect(jsonPath("$.blocks[0].content").exists());
    }

    /**
     * A block whose {@code source_of_data} this service does not implement is reported, not silently
     * dropped or misrouted to whichever provider shares its content type (ADR-021).
     */
    @Test
    void resolve_unsupportedSourceOfData_isUnavailableWithTheReason() throws Exception {
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();
        when(productsListService.resolveAll(anyList())).thenReturn(List.of());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(unsupportedBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.blocks[0].reason").value("UNSUPPORTED_SOURCE_OF_DATA"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist());
    }

    // ── conditional_show_hide ────────────────────────────────────────────────────────────────────

    /** The rule travels in the body ({@code ms_params}); the controller hands it to the use case as-is. */
    @Test
    void resolve_blockWithConditionalShowHide_passesTheRuleToTheUseCase() throws Exception {
        stubBatch(List.of(available(JW_BLOCK, null)));
        Map<String, Object> block = new java.util.LinkedHashMap<>(jewelBlock(JW_BLOCK));
        block.put("conditional_show_hide",
                Map.of("active", "true", "action", "show", "header_name", "x-ab-carousel"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(block)))
                .andExpect(status().isOk());

        assertThat(capturedQueries().get(0).conditionalShowHide())
                .isEqualTo(new ConditionalShowHide(true, "show", "x-ab-carousel", null));
    }

    @Test
    void resolve_blockWithoutConditionalShowHide_passesNoRule() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk());

        assertThat(capturedQueries().get(0).conditionalShowHide()).isEqualTo(ConditionalShowHide.NONE);
    }

    /** The other arm of the experiment: legitimately empty, so the frontend drops it and never retries. */
    @Test
    void resolve_cohortExcluded_isEmptyWithTheReason() throws Exception {
        when(productsListService.resolveAll(anyList())).thenReturn(List.of(
                ProductsListResolution.empty(JW_BLOCK, BlockDropReason.CONDITIONAL_SHOW_HIDE_EXCLUDED)));
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(jewelBlock(JW_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.blocks[0].reason").value("CONDITIONAL_SHOW_HIDE_EXCLUDED"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist());
    }

    // ── provider routing from source_of_data ─────────────────────────────────────────────────────

    @Test
    void resolve_jewelBlock_buildsJewelSettingsFromItsModelConfigAndHeaders() throws Exception {
        stubBatch(List.of(available(JW_BLOCK, null)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(jewelBlock(JW_BLOCK))))
                .andExpect(status().isOk());

        JewelSettings settings = (JewelSettings) capturedQueries().get(0).provider();
        assertThat(settings.userId()).isEqualTo("jml-1");
        assertThat(settings.deviceId()).isEqualTo("dev-1");
        assertThat(settings.modelConfig().model()).isEqualTo("global-tps-sales");
    }

    @Test
    void resolve_salesforceBlock_buildsSalesforceSettingsFromItsCarouselAndAtgId() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk());

        SalesforceSettings settings = (SalesforceSettings) capturedQueries().get(0).provider();
        assertThat(settings.userId()).isEqualTo(USER_ID);
        assertThat(settings.carousel()).isEqualTo("CMSTePuedeGustar");
    }

    @Test
    void resolve_salesforceV2Block_buildsV2SettingsFromItsConfigAndHeaders() throws Exception {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(new SessionContext(
                false, "LP", "WEB", "es-mx", null,
                Map.of(SF_USER_ID_HEADER, USER_ID, SF_DEVICE_ID_HEADER, "sf-dev-1"), SESSION_ID));
        stubBatch(List.of(available(SF2_BLOCK, null)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceV2Block(SF2_BLOCK))))
                .andExpect(status().isOk());

        SalesforceV2Settings settings = (SalesforceV2Settings) capturedQueries().get(0).provider();
        assertThat(settings.model()).isEqualTo("home-top-sellers");
        assertThat(settings.requiresDeviceId()).isTrue();
        assertThat(settings.userId()).isEqualTo(USER_ID);
        assertThat(settings.deviceId()).isEqualTo("sf-dev-1");
    }

    /** Parity with commons: V2 takes its identity from the headers alone, with no SessionId gate. */
    @Test
    void resolve_salesforceV2Block_withoutSessionId_stillForwardsTheHeaderIdentity() throws Exception {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(new SessionContext(
                false, "LP", "WEB", "es-mx", null, Map.of(SF_DEVICE_ID_HEADER, "sf-dev-1"), null));
        stubBatch(List.of(available(SF2_BLOCK, null)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceV2Block(SF2_BLOCK))))
                .andExpect(status().isOk());

        SalesforceV2Settings settings = (SalesforceV2Settings) capturedQueries().get(0).provider();
        assertThat(settings.userId()).isNull();
        assertThat(settings.individualId()).isEqualTo("sf-dev-1");
    }

    @Test
    void resolve_salesforceV2BlockWithConditionalShowHide_passesTheRuleToTheUseCase() throws Exception {
        stubBatch(List.of(available(SF2_BLOCK, null)));
        Map<String, Object> block = new java.util.LinkedHashMap<>(salesforceV2Block(SF2_BLOCK));
        block.put("conditional_show_hide",
                Map.of("active", "true", "action", "hide", "cookie_name", "ab_carousel"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body(block)))
                .andExpect(status().isOk());

        assertThat(capturedQueries().get(0).conditionalShowHide())
                .isEqualTo(new ConditionalShowHide(true, "hide", null, "ab_carousel"));
    }

    @Test
    void resolve_noSessionAndNoHeader_forwardsNullAtgUserId() throws Exception {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(NO_SESSION_NO_HEADER);
        stubBatch(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.BELOW_MIN_PRODUCTS)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk());

        assertThat(((SalesforceSettings) capturedQueries().get(0).provider()).userId()).isNull();
    }

    /**
     * ADR-041, first condition: the header alone is not an identity. Without a {@code SessionId} cookie
     * Salesforce must not resolve, because Salesforce V1 only serves logged-in shoppers.
     *
     * <p>The block is still answered — it comes back {@code EMPTY} with its reason, exactly as any
     * carousel that resolved to nothing — rather than being dropped from the batch or failed.</p>
     */
    @Test
    void resolve_headerWithoutSessionId_forwardsNullAtgUserId_andStillAnswersTheBlock() throws Exception {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(NO_SESSION_ID);
        stubBatch(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.BELOW_MIN_PRODUCTS)));
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockId").value(SF_BLOCK))
                .andExpect(jsonPath("$.blocks[0].status").value("EMPTY"))
                .andExpect(jsonPath("$.blocks[0].reason").value("BELOW_MIN_PRODUCTS"))
                .andExpect(jsonPath("$.blocks[0].content").doesNotExist());

        assertThat(((SalesforceSettings) capturedQueries().get(0).provider()).userId()).isNull();
    }

    /** ADR-041, second condition: the header *is* the profile id, so its absence resolves nothing. */
    @Test
    void resolve_sessionWithoutSalesforceHeader_forwardsNullAtgUserId() throws Exception {
        when(sessionContextPort.currentContextWithoutIdentity()).thenReturn(NO_SF_HEADER);
        stubBatch(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.BELOW_MIN_PRODUCTS)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk());

        assertThat(((SalesforceSettings) capturedQueries().get(0).provider()).userId()).isNull();
    }

    /** The supported shape: SessionId + x-sf-user-id, and that header value is what reaches Salesforce. */
    @Test
    void resolve_sessionAndHeader_forwardsTheHeaderProfileId() throws Exception {
        stubBatch(List.of(ProductsListResolution.empty(SF_BLOCK, BlockDropReason.BELOW_MIN_PRODUCTS)));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk());

        assertThat(((SalesforceSettings) capturedQueries().get(0).provider()).userId())
                .isEqualTo(USER_ID);
    }

    /**
     * A source ms-clp-blp does not implement must never be routed to whichever provider happens to share
     * the content type — the misrouting bug ADR-021 fixed.
     */
    @Test
    void resolve_unsupportedSource_isReportedUnavailableAndNeverRouted() throws Exception {
        when(productsListService.resolveAll(anyList())).thenReturn(List.of());
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(unsupportedBlock("bltrecentlyviewed"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].blockId").value("bltrecentlyviewed"))
                .andExpect(jsonPath("$.blocks[0].status").value("UNAVAILABLE"));

        assertThat(capturedQueries()).isEmpty();
    }

    @Test
    void resolve_blockWithNoSourceOfData_routesToSalesforceForLegacyParity() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("uid", SF_BLOCK, "salesforce_carousel", "CMSLegacy"))))
                .andExpect(status().isOk());

        assertThat(capturedQueries().get(0).provider()).isInstanceOf(SalesforceSettings.class);
    }

    /** groupby is aliased to the Vertex provider rather than left unrouted (ADR-055). */
    @Test
    void resolve_groupbyTaggedBlock_routesToVertexSettings() throws Exception {
        stubBatch(List.of(available("bltgroupby", "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("uid", "bltgroupby", "source_of_data", "groupby",
                                "products_data", Map.of("category", "cat-1")))))
                .andExpect(status().isOk());

        assertThat(capturedQueries().get(0).provider()).isInstanceOf(VertexSettings.class);
    }

    // ── Cache-Control ────────────────────────────────────────────────────────────────────────────

    @Test
    void resolve_allBlocksSessionIndependent_isEdgeCacheable() throws Exception {
        stubBatch(List.of(available(JW_BLOCK, null)));
        when(productsListService.allSessionIndependent(anyList())).thenReturn(true);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(jewelBlock(JW_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"));
    }

    @Test
    void resolve_anyPersonalisedBlock_isNoStore() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));
        when(productsListService.allSessionIndependent(anyList())).thenReturn(false);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void resolve_unroutableBlockPresent_isNoStoreEvenIfTheRestWouldBeCacheable() throws Exception {
        when(productsListService.resolveAll(anyList())).thenReturn(List.of());
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();
        when(productsListService.allSessionIndependent(anyList())).thenReturn(true);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(unsupportedBlock("bltrecentlyviewed"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    // ── envelope, headers, validation, errors ────────────────────────────────────────────────────

    @Test
    void resolve_requestIdEchoedInResponseHeader() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK)))
                        .header("x-request-id", "req-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", "req-42"));
    }

    @Test
    void resolve_noRequestId_generatesOneInResponseHeader() throws Exception {
        stubBatch(List.of(available(SF_BLOCK, "t")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    @Test
    void resolve_emptyArray_returns400() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }

    /** A total failure still surfaces through GlobalExceptionHandler rather than as a partial 200. */
    @Test
    void resolve_circuitBreakerOpenForTheWholeCall_returns503() throws Exception {
        when(productsListService.resolveAll(anyList()))
                .thenThrow(new ServiceUnavailableException("breaker open", null));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void resolve_unexpectedError_returns500WithoutInternalDetail() throws Exception {
        when(productsListService.resolveAll(anyList())).thenThrow(new RuntimeException("boom"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(body(salesforceBlock(SF_BLOCK))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("boom"))));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private void stubBatch(List<ProductsListResolution> resolutions) {
        when(productsListService.resolveAll(anyList())).thenReturn(resolutions);
        when(productsListMapper.toBatchResponse(anyList())).thenCallRealMethod();
        when(productsListMapper.toResponse(any())).thenCallRealMethod();
    }

    @SuppressWarnings("unchecked")
    private List<ProductsListQuery> capturedQueries() {
        ArgumentCaptor<List<ProductsListQuery>> captor = ArgumentCaptor.forClass(List.class);
        verify(productsListService).resolveAll(captor.capture());
        return captor.getValue();
    }

    private static ProductsListResolution available(String blockId, String title) {
        return ProductsListResolution.available(blockId, title, title == null ? null : "campaign",
                List.of(product("sku-001", "Product A")));
    }

    private static Map<String, Object> salesforceBlock(String uid) {
        return Map.of("uid", uid, "source_of_data", "salesforce",
                "salesforce_carousel", "CMSTePuedeGustar");
    }

    private static Map<String, Object> jewelBlock(String uid) {
        return Map.of("uid", uid, "source_of_data", "jewel",
                "jewel_model_config", Map.of("jewel_model", "global-tps-sales"));
    }

    private static Map<String, Object> salesforceV2Block(String uid) {
        return Map.of("uid", uid, "source_of_data", "salesforce_v2",
                "salesforce_v2_config", Map.of("model", "home-top-sellers", "requires_device_id", "true"));
    }

    /** A source this service does not implement, so the controller cannot route the block at all. */
    private static Map<String, Object> unsupportedBlock(String uid) {
        return Map.of("uid", uid, "source_of_data", "recently_viewed");
    }

    @SafeVarargs
    private static String body(Map<String, Object>... blocks) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < blocks.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(toJson(blocks[i]));
        }
        return json.append(']').toString();
    }

    private static String toJson(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(entry.getKey()).append("\":").append(toJson(entry.getValue()));
            }
            return json.append('}').toString();
        }
        return "\"" + value + "\"";
    }

    /** Minimal product carrying the published contract; only asserted fields are set. */
    private static ProductRecommendation product(String productId, String name) {
        ProductRecommendation p = new ProductRecommendation();
        p.setProductId(productId);
        p.setName(name);
        return p;
    }
}
