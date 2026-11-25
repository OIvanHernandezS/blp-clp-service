package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.landing.errors.CategoryNotFoundException;
import com.liverpool.ms_clp_blp.landing.models.CategoryData;
import com.liverpool.ms_clp_blp.landing.models.CategoryMetadata;
import com.liverpool.ms_clp_blp.landing.ports.LandingService;
import com.liverpool.ms_clp_blp.shared.session.SessionContext;
import com.liverpool.ms_clp_blp.shared.session.SessionContextPort;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link CategoryControllerV1} against the real security chain (CORS, Basic) and the
 * real {@link CategoryDataMapper}, so the JSON asserted here is the JSON the storefront receives.
 */
@WebMvcTest(CategoryControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class, CategoryDataMapper.class})
@WithServiceCredential
class CategoryControllerV1Test {

    private static final String URL = "/categories?categoryId=cat5020010";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LandingService landingService;

    @MockitoBean
    private SessionContextPort sessionContextPort;

    @MockitoBean
    private CmsProperties contentstackProperties;

    @BeforeEach
    void setUp() {
        when(sessionContextPort.currentContext()).thenReturn(new SessionContext(false, "SB", "WEB", "es-mx"));
    }

    // ── 200 ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    void getCategory_returnsTheCategoryAtTheRoot_inTheCategoryDataShape() throws Exception {
        when(landingService.getCategory(any(), any())).thenReturn(category());

        mockMvc.perform(get(URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value("CAT5020010"))
                .andExpect(jsonPath("$.name").value("Ropa para hombre"))
                .andExpect(jsonPath("$.breadcrumb").value("home > Ropa para hombre"))
                .andExpect(jsonPath("$.metadata.redirects").isArray())
                .andExpect(jsonPath("$.redirectContent").doesNotExist());
    }

    /** What the storefront renders per tile (image + title) and needs to navigate to the child. */
    @Test
    void getCategory_everyChildCarriesImageTitleAndNavigationMetadata() throws Exception {
        when(landingService.getCategory(any(), any())).thenReturn(category());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.children.length()").value(1))
                .andExpect(jsonPath("$.children[0].name").value("Camisas"))
                .andExpect(jsonPath("$.children[0].urlAddress").value("/tienda/camisas/cat5020011"))
                .andExpect(jsonPath("$.children[0].metadata.imageUrl").value("https://img/camisas.jpg"))
                .andExpect(jsonPath("$.children[0].metadata.categoryImage").value("https://img/camisas-cat.jpg"))
                .andExpect(jsonPath("$.children[0].metadata.viewUrl").value("/tienda/camisas/cat5020011"))
                .andExpect(jsonPath("$.children[0].metadata.showpath").value("PLP"))
                .andExpect(jsonPath("$.children[0].metadata.applyCLPRedirect").value(true))
                .andExpect(jsonPath("$.children[0].breadcrumb").doesNotExist());
    }

    @Test
    void getCategory_readsTheBrandFromTheSession_andTheIdFromTheQuery() throws Exception {
        when(landingService.getCategory(any(), any())).thenReturn(category());

        mockMvc.perform(get(URL)).andExpect(status().isOk());

        verify(landingService).getCategory("SB", "cat5020010");
    }

    /** Depends only on brand + category, so the edge may share every 200. */
    @Test
    void getCategory_isSharedWithTheEdge() throws Exception {
        when(landingService.getCategory(any(), any())).thenReturn(category());

        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public"))
                .andExpect(header().string("x-request-id", notNullValue()));
    }

    /** A CORS request must not leave Origin in Vary, or Akamai refuses to store the response. */
    @Test
    void getCategory_corsRequest_isAllowed_andKeepsOriginOutOfVary() throws Exception {
        when(landingService.getCategory(any(), any())).thenReturn(category());

        MvcResult result = mockMvc.perform(get(URL).header("Origin", "https://www.liverpool.com.mx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://www.liverpool.com.mx"))
                .andReturn();

        assertThat(result.getResponse().getHeaders("Vary"))
                .noneMatch(vary -> vary.toLowerCase().contains("origin"));
    }

    // ── errors ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void getCategory_unknownCategory_returns404ProblemDetail_thatIsNotShared() throws Exception {
        when(landingService.getCategory(any(), any()))
                .thenThrow(new CategoryNotFoundException("not found", "brand=SB categoryId=cat5020010"));

        mockMvc.perform(get(URL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CATEGORY_NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    void getCategory_missingCategoryId_returns400() throws Exception {
        mockMvc.perform(get("/categories"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(landingService, never()).getCategory(any(), any());
    }

    @Test
    void getCategory_categoryIdThatCouldEscapeTheIndexerPath_returns400() throws Exception {
        for (String bad : List.of("cat%2F..%2Fadmin", "cat?x=1", "", "a".repeat(65))) {
            mockMvc.perform(get("/categories").param("categoryId", bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
        }

        verify(landingService, never()).getCategory(any(), any());
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private static CategoryData category() {
        CategoryData child = new CategoryData("CAT5020011", "Camisas", "Camisas", "/tienda/camisas/cat5020011",
                false, List.of("cat5020001", "cat5020010"),
                metadata(3, "cat5020010", "Camisas", "/tienda/camisas/cat5020011", "https://img/camisas.jpg",
                        "PLP", "CAT5020011", "https://img/camisas-cat.jpg", true),
                List.of(), null, null);
        return new CategoryData("CAT5020010", "Ropa para hombre", "Ropa para hombre",
                "/tienda/ropa-para-hombre/cat5020010", false, List.of("cat5020001"),
                metadata(2, "cat5020001", "Ropa para hombre", "/tienda/ropa-para-hombre/cat5020010", "",
                        "CLP", "CAT5020010", "", false),
                List.of(child), "home > Ropa para hombre", null);
    }

    private static CategoryMetadata metadata(int level, String parent, String title, String viewUrl,
                                             String imageUrl, String showpath, String categoryId,
                                             String categoryImage, boolean applyClpRedirect) {
        return new CategoryMetadata(level, parent, title, false, viewUrl, imageUrl, showpath, categoryId,
                "Not Specified", 0, categoryId, null, "CLP", null, null, null, false, categoryImage, true,
                applyClpRedirect);
    }
}
