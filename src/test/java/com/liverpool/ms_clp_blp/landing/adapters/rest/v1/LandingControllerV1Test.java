package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.landing.LandingProperties;
import com.liverpool.ms_clp_blp.landing.errors.LandingDefinitionNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.LandingPage;
import com.liverpool.ms_clp_blp.landing.models.LandingPageQuery;
import com.liverpool.ms_clp_blp.landing.models.PageType;
import com.liverpool.ms_clp_blp.landing.ports.LandingService;
import com.liverpool.ms_clp_blp.shared.errors.ContentServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.ContentOrigin;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link LandingControllerV1} — loads only the web layer.
 * {@link SecurityConfig} is imported explicitly so this test runs against the real chain (CORS,
 * response headers, HTTP Basic) rather than the one {@code @WebMvcTest} would auto-configure;
 * {@link WithServiceCredential} supplies the credential it now requires. The 401 path itself is
 * asserted in {@code SecurityConfigTest}, not here.
 */
@WebMvcTest(LandingControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
@WithServiceCredential
class LandingControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LandingService landingService;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private LandingPageMapper landingPageMapper;

    @MockitoBean
    private CmsProperties contentstackProperties;

    /** Supplies the logged-in eager offset; the Mockito default of {@code 0} is the shipped value. */
    @MockitoBean
    private LandingProperties landingProperties;

    private static final String BLP_URL = "/blp/es-mx/tienda/esteelauder/catst26617625";
    private static final String CLP_URL = "/clp/es-mx/tienda/belleza/cat5020010";
    private static final LandingPage EMPTY_PAGE = new LandingPage(PageType.BLP, "es-mx", Map.of(), Map.of(), null);

    private static final SessionContext GUEST_SESSION = new SessionContext(false, "LP", "WEB", "es-mx");
    private static final SessionContext AUTH_SESSION   = new SessionContext(true,  "LP", "WEB", "es-mx");

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void clearOriginContext() {
        CmsResponseMetadataContext.clear();
    }

    /** Builds a root passthrough response ({@code locale} + {@code blocks}) for mapper stubs. */
    private static LandingPageResponse responseWith(List<Map<String, Object>> blocks) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("locale", "es-mx");
        root.put("blocks", blocks);
        return new LandingPageResponse(root);
    }

    private static LandingPageResponse emptyResponse() {
        return responseWith(List.of());
    }

    // ── 200 happy paths ──────────────────────────────────────────────────────────────────────────

    @Test
    void getBrandLandingPage_guestSession_returns200WithJsonBody() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.locale").value("es-mx"))
                .andExpect(jsonPath("$.blocks").isArray());
    }

    @Test
    void getBrandLandingPage_authenticatedSession_returns200() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(AUTH_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).header("x-authenticated", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void getBrandLandingPage_inboundRequestId_echoedInResponseHeader() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).header("x-request-id", "test-req-id"))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", equalTo("test-req-id")));
    }

    @Test
    void getBrandLandingPage_noRequestId_generatesUuidInResponseHeader() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk())
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    @Test
    void getBrandLandingPage_responseContainsFlatBlocksAtTheRoot() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);

        Map<String, Object> block = Map.of("_content_type_uid", "banner", "uid", "uid-1", "title", "Hello");
        when(landingPageMapper.toResponse(any())).thenReturn(responseWith(List.of(block)));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0]._content_type_uid").value("banner"))
                .andExpect(jsonPath("$.blocks[0].uid").value("uid-1"))
                .andExpect(jsonPath("$.blocks[0].blockType").doesNotExist());
    }

    @Test
    void getBrandLandingPage_originRecordedByTheContentChain_echoedInResponseHeader() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        // The mocked use case bypasses the real ContentSourceRouter/CachedLandingContentPort chain, so
        // the origin this controller reads back is set here the same way that chain would have.
        when(landingService.getLandingPage(any())).thenAnswer(invocation -> {
            CmsResponseMetadataContext.set(ContentOrigin.CAFFEINE);
            return EMPTY_PAGE;
        });
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk())
                .andExpect(header().string("x-data-origin", "caffeine"));
    }

    @Test
    void getBrandLandingPage_noOriginRecorded_headerAbsent() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("x-data-origin"));
    }

    // ── edge cacheability ─────────────────────────────────────────────────────────────────────────
    //
    // Two responses are shareable and they are the two slots the Akamai property keys on: the guest
    // page and the signed-in skeleton. Everything else must stay out of the edge, because it would be
    // filed under a variant the origin did not actually produce.

    /** Builds a session carrying an {@code x-is-logged-in} declaration. */
    private static SessionContext sessionDeclaring(boolean authenticated, String claim) {
        return sessionDeclaring(authenticated, claim, null);
    }

    /** Same, also carrying a well-formed {@code SessionId} cookie value. */
    private static SessionContext sessionDeclaring(boolean authenticated, String claim, String sessionId) {
        return new SessionContext(authenticated, "LP", "WEB", "es-mx",
                Map.of(SessionContext.HEADER_IS_LOGGED_IN, claim), sessionId);
    }

    private static final String SESSION_ID = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed.c2lnbmF0dXJl";

    private LandingPageQuery capturedQuery() {
        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        return captor.getValue();
    }

    private void stubLandingPage(SessionContext session) {
        when(sessionContextPort.currentContext()).thenReturn(session);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());
    }

    @Test
    void getBrandLandingPage_declaredGuest_isSharedWithTheEdge() throws Exception {
        stubLandingPage(sessionDeclaring(false, "false"));

        mockMvc.perform(get(BLP_URL).header(SessionContext.HEADER_IS_LOGGED_IN, "false"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public"))
                // The security default would otherwise block the edge on these two alone.
                .andExpect(header().doesNotExist("Pragma"))
                .andExpect(header().doesNotExist("Expires"));
    }

    @Test
    void getBrandLandingPage_declaredLoggedInSkeleton_isSharedWithTheEdge() throws Exception {
        stubLandingPage(sessionDeclaring(true, "true", SESSION_ID));

        mockMvc.perform(get(BLP_URL).header(SessionContext.HEADER_IS_LOGGED_IN, "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public"));
    }

    @Test
    void getBrandLandingPage_declaredLoggedInWithoutSessionCookie_isNotShared() throws Exception {
        // Regression (2026-10-13): the declaration alone makes this authenticated, but with no SessionId
        // the edge's signed-in rule does not match and the request falls through to a catch-all
        // rule, which does not key on x-brand-id — one brand's page served to all.
        stubLandingPage(sessionDeclaring(true, "true"));

        mockMvc.perform(get(BLP_URL).header(SessionContext.HEADER_IS_LOGGED_IN, "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void getBrandLandingPage_declaredLoggedInWithEagerOffset_isNotShared() throws Exception {
        // Above offset 0 the resolvers run and inline personalised payloads, so the page stops being
        // a skeleton and stops being shareable.
        stubLandingPage(sessionDeclaring(true, "true"));

        mockMvc.perform(get(BLP_URL)
                        .param("eagerOffset", "3")
                        .header(SessionContext.HEADER_IS_LOGGED_IN, "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void getBrandLandingPage_declaredGuestWithEagerOffset_isStillShared() throws Exception {
        // A guest's blocks are audience-generic, so a fully inlined guest page is the same for everyone.
        stubLandingPage(sessionDeclaring(false, "false"));

        mockMvc.perform(get(BLP_URL)
                        .param("eagerOffset", "3")
                        .header(SessionContext.HEADER_IS_LOGGED_IN, "false"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public"));
    }

    @Test
    void getBrandLandingPage_noDeclaration_isNotShared() throws Exception {
        // No x-is-logged-in: the edge has no slot for this request, so it is served uncached.
        stubLandingPage(GUEST_SESSION);

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    void getBrandLandingPage_errorResponse_keepsTheUncacheableSecurityDefault() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(sessionDeclaring(false, "false"));
        when(landingService.getLandingPage(any()))
                .thenThrow(new ContentServiceUnavailableException(
                        "content-service is down", "upstream 500", null));

        // An error never reaches the controller's builder, so it keeps Spring Security's no-store.
        mockMvc.perform(get(BLP_URL).header(SessionContext.HEADER_IS_LOGGED_IN, "false"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    // ── error scenarios ───────────────────────────────────────────────────────────────────────────

    @Test
    void getBrandLandingPage_errorResponse_dataOriginHeaderAbsent() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any()))
                .thenThrow(new ContentServiceUnavailableException(
                        "content-service is down", "upstream 500", null));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isBadGateway())
                .andExpect(header().doesNotExist("x-data-origin"));
    }

    @Test
    void getBrandLandingPage_contentServiceUnavailable_returns502ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any()))
                .thenThrow(new ContentServiceUnavailableException(
                        "content-service is down", "upstream 500", null));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("CONTENT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getBrandLandingPage_pageNotFound_returns404ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any()))
                .thenThrow(new LandingDefinitionNotFoundException("not found", "brand=LP locale=es-mx"));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LANDING_PAGE_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void getBrandLandingPage_circuitBreakerOpen_returns503ProblemDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any()))
                .thenThrow(new ServiceUnavailableException("breaker open", null));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void getBrandLandingPage_unexpectedError_returns500WithoutInternalDetail() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any()))
                .thenThrow(new RuntimeException("secret internal detail: password=abc123"));

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("UNEXPECTED_ERROR"))
                .andExpect(jsonPath("$.detail").value(
                        "An unexpected error occurred. Please try again later."));
    }


    // ── routing: page type, locale, path and the favourite-store cookie ────────────────────────────

    @Test
    void getBrandLandingPage_passesPageTypeLocaleAndPathToTheQuery() throws Exception {
        stubLandingPage(GUEST_SESSION);

        mockMvc.perform(get(BLP_URL)).andExpect(status().isOk());

        LandingPageQuery query = capturedQuery();
        assertThat(query.pageType()).isEqualTo(PageType.BLP);
        assertThat(query.locale()).isEqualTo("es-mx");
        assertThat(query.categoryId()).isEqualTo("catst26617625");
        assertThat(query.favoriteStoreId()).isNull();
    }

    @Test
    void getCategoryLandingPage_composesAClp() throws Exception {
        stubLandingPage(GUEST_SESSION);

        mockMvc.perform(get(CLP_URL)).andExpect(status().isOk());

        LandingPageQuery query = capturedQuery();
        assertThat(query.pageType()).isEqualTo(PageType.CLP);
        assertThat(query.categoryId()).isEqualTo("cat5020010");
    }

    @Test
    void getBrandLandingPage_invalidLocale_returns400ValidationError() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);

        mockMvc.perform(get("/blp/ES_MX/tienda/esteelauder/catst26617625"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    /**
     * The cookie's store ends up in the page's analytics values, so a page composed with it is that
     * shopper's page — sharing it at the edge would report one store for everyone.
     */
    @Test
    void getBrandLandingPage_favoriteStoreCookie_isPassedOnAndMakesThePagePersonal() throws Exception {
        stubLandingPage(sessionDeclaring(false, "false"));

        mockMvc.perform(get(BLP_URL)
                        .header(SessionContext.HEADER_IS_LOGGED_IN, "false")
                        .cookie(new jakarta.servlet.http.Cookie("favoriteStore", "7")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store, private"));

        assertThat(capturedQuery().favoriteStoreId()).isEqualTo("7");
    }

    // ── eager resolution controls ────────────────────────────────────────────────────────────────

    @Test
    void getBrandLandingPage_eagerOffsetParam_passedToQuery() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).param("eagerOffset", "2")).andExpect(status().isOk());

        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        assertThat(captor.getValue().eagerOffsetOverride()).isEqualTo(2);
    }

    @Test
    void getBrandLandingPage_negativeEagerOffset_returns400ValidationError() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);

        mockMvc.perform(get(BLP_URL).param("eagerOffset", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void getBrandLandingPage_forwardsSessionHeadersToQuery() throws Exception {
        // The x-* header snapshot is captured by SessionContextAdapter; the controller forwards
        // session.headers() into the query. Sensitive headers never reach the session snapshot.
        SessionContext sessionWithHeaders = new SessionContext(
                true, "LP", "WEB", "es-mx", Map.of("x-user-id", "user-42"), null);
        when(sessionContextPort.currentContext()).thenReturn(sessionWithHeaders);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL))
                .andExpect(status().isOk());

        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        assertThat(captor.getValue().gatewayHeaders()).containsEntry("x-user-id", "user-42");
        assertThat(captor.getValue().gatewayHeaders()).doesNotContainKey("authorization");
    }

    // ── brand: x-brand-id header only, like every other endpoint ───────────────────────────────────

    /**
     * Regression carried over from ms-home: its page endpoint briefly read brand from a {@code ?brand=}
     * query param, which made it the one endpoint that ignored {@code x-brand-id}. The session's brand — resolved from the
     * header by {@code SessionContextAdapter}, with its own default-brand fallback — must reach the
     * query untouched, and a stray {@code ?brand=} must change nothing.
     */
    @Test
    void getBrandLandingPage_brandComesFromSession_andBrandQueryParamIsIgnored() throws Exception {
        when(sessionContextPort.currentContext())
                .thenReturn(new SessionContext(false, "SB", "WEB", "es-mx"));
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).param("brand", "LP")).andExpect(status().isOk());

        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        assertThat(captor.getValue().session().brand()).isEqualTo("SB");
    }

    // ── session resolution (ADR-067) ─────────────────────────────────────────────────────────────
    //
    // A landing page folds the caller's x-is-logged-in declaration into authenticated, because it
    // only needs guest-vs-logged-in.

    @Test
    void getBrandLandingPage_declaredLoggedIn_isTreatedAsAuthenticated()
            throws Exception {
        when(sessionContextPort.currentContext())
                .thenReturn(sessionDeclaring(false, "true"));
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL).header(SessionContext.HEADER_IS_LOGGED_IN, "true"))
                .andExpect(status().isOk());

        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        assertThat(captor.getValue().session().authenticated()).isTrue();
    }

    @Test
    void getBrandLandingPage_noDeclaration_staysGuest() throws Exception {
        when(sessionContextPort.currentContext()).thenReturn(GUEST_SESSION);
        when(landingService.getLandingPage(any())).thenReturn(EMPTY_PAGE);
        when(landingPageMapper.toResponse(any())).thenReturn(emptyResponse());

        mockMvc.perform(get(BLP_URL)).andExpect(status().isOk());

        ArgumentCaptor<LandingPageQuery> captor = ArgumentCaptor.forClass(LandingPageQuery.class);
        Mockito.verify(landingService).getLandingPage(captor.capture());
        assertThat(captor.getValue().session().authenticated()).isFalse();
    }
}
