package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.rest.v1;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_clp_blp.shared.cachepurge.CachePurgeProperties;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeTargetKind;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgeService;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the operator-triggered purge endpoints.
 *
 * <p>Authentication itself is not asserted here — {@code SecurityConfigTest} owns that contract, and
 * {@code @WithServiceCredential} just satisfies the chain. What is this controller's own: that the
 * {@code action}/{@code network} query parameters reach the port as chosen, that a typo is a 400
 * rather than a silent default, and that the kill switch refuses before anything is attempted.</p>
 */
@WebMvcTest(CachePurgeOpsControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
@WithServiceCredential
class CachePurgeOpsControllerV1Test {

    private static final String PURGE_ALL_PATH = "/ops/cache-purge";
    private static final String PURGE_PATHS_PATH = "/ops/cache-purge/paths";
    private static final String PURGE_TAGS_PATH = "/ops/cache-purge/tags";

    private static final String HOME = "https://www.liverpool.com.mx/home";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CachePurgeService cachePurgeService;

    @MockitoBean
    private CachePurgeProperties properties;

    @BeforeEach
    void enableByDefault() {
        when(properties.enabled()).thenReturn(true);
        when(properties.defaultAction()).thenReturn(PurgeAction.INVALIDATE);
        when(properties.defaultNetwork()).thenReturn(PurgeNetwork.PRODUCTION);
    }

    @Test
    void purgingEverythingReturnsTheReportAndEchoesTheRequestId() throws Exception {
        when(cachePurgeService.purgeAll(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION))
                .thenReturn(report());

        mockMvc.perform(post(PURGE_ALL_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("INVALIDATE"))
                .andExpect(jsonPath("$.network").value("PRODUCTION"))
                .andExpect(jsonPath("$.totalObjects").value(2))
                .andExpect(jsonPath("$.purges[0].targetKind").value("URL"))
                .andExpect(jsonPath("$.purges[0].objects[0]").value(HOME))
                .andExpect(jsonPath("$.purges[0].purgeId").value("purge-1"))
                .andExpect(jsonPath("$.purges[1].targetKind").value("TAG"))
                .andExpect(jsonPath("$.purges[1].objects[0]").value("ms-clp-blp"))
                .andExpect(header().string("x-request-id", notNullValue()));

        verify(cachePurgeService).purgeAll(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);
    }

    @Test
    void theOperatorsActionAndNetworkReachThePort() throws Exception {
        when(cachePurgeService.purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING)).thenReturn(report());

        mockMvc.perform(post(PURGE_ALL_PATH).param("action", "delete").param("network", "staging"))
                .andExpect(status().isOk());

        verify(cachePurgeService).purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING);
    }

    @Test
    void omittingBothParametersFallsBackToTheConfiguredDefaults() throws Exception {
        when(properties.defaultAction()).thenReturn(PurgeAction.DELETE);
        when(properties.defaultNetwork()).thenReturn(PurgeNetwork.STAGING);
        when(cachePurgeService.purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING)).thenReturn(report());

        mockMvc.perform(post(PURGE_ALL_PATH)).andExpect(status().isOk());

        verify(cachePurgeService).purgeAll(PurgeAction.DELETE, PurgeNetwork.STAGING);
    }

    @Test
    void thePathsAndTagsEndpointsDriveTheirOwnPortMethods() throws Exception {
        when(cachePurgeService.purgePaths(any(), any())).thenReturn(report());
        when(cachePurgeService.purgeTags(any(), any())).thenReturn(report());

        mockMvc.perform(post(PURGE_PATHS_PATH)).andExpect(status().isOk());
        mockMvc.perform(post(PURGE_TAGS_PATH)).andExpect(status().isOk());

        verify(cachePurgeService).purgePaths(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);
        verify(cachePurgeService).purgeTags(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION);
    }

    /** Defaulting a typo would purge the wrong network and look like a purge that did nothing. */
    @Test
    void anUnknownNetworkIs400AndNamesWhatIsAccepted() throws Exception {
        mockMvc.perform(post(PURGE_ALL_PATH).param("network", "prod"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(cachePurgeService, never()).purgeAll(any(), any());
    }

    @Test
    void anUnknownActionIs400() throws Exception {
        mockMvc.perform(post(PURGE_ALL_PATH).param("action", "evict"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(cachePurgeService, never()).purgeAll(any(), any());
    }

    @Test
    void disabledIs409ForAllThreeEndpoints() throws Exception {
        when(properties.enabled()).thenReturn(false);

        for (String path : List.of(PURGE_ALL_PATH, PURGE_PATHS_PATH, PURGE_TAGS_PATH)) {
            mockMvc.perform(post(path))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CACHE_PURGE_DISABLED"));
        }

        verify(cachePurgeService, never()).purgeAll(any(), any());
        verify(cachePurgeService, never()).purgePaths(any(), any());
        verify(cachePurgeService, never()).purgeTags(any(), any());
    }

    // ── POST /ops/cache-purge/objects ─────────────────────────────────────────────────────────────

    private static final String PURGE_OBJECTS_PATH = "/ops/cache-purge/objects";

    /** The exact body ms-akamai-client takes, so site/channel/alias must be tolerated. */
    private static final String OBJECTS_BODY = """
            {"site": "liverpool", "channel": "web", "alias": "liverpool-app-search",
             "type": "delete", "urls": ["%s"], "tags": ["ms-clp-blp"]}""".formatted(HOME);

    @Test
    void theBodysObjectsAndTypeReachThePortAndItsUnknownFieldsAreIgnored() throws Exception {
        when(cachePurgeService.purgeObjects(any(), any(), any(), any())).thenReturn(report());

        mockMvc.perform(post(PURGE_OBJECTS_PATH).param("network", "staging")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(OBJECTS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purges[0].purgeId").value("purge-1"))
                .andExpect(header().string("x-request-id", notNullValue()));

        verify(cachePurgeService).purgeObjects(PurgeAction.DELETE, PurgeNetwork.STAGING, List.of(HOME),
                List.of("ms-clp-blp"));
    }

    @Test
    void omittingTypeAndNetworkFallsBackToTheConfiguredDefaults() throws Exception {
        when(cachePurgeService.purgeObjects(any(), any(), any(), any())).thenReturn(report());

        mockMvc.perform(post(PURGE_OBJECTS_PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"urls\": [\"" + HOME + "\"]}"))
                .andExpect(status().isOk());

        verify(cachePurgeService).purgeObjects(PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION,
                List.of(HOME), null);
    }

    @Test
    void anUnknownTypeInTheBodyIs400() throws Exception {
        mockMvc.perform(post(PURGE_OBJECTS_PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"evict\", \"urls\": [\"" + HOME + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));

        verify(cachePurgeService, never()).purgeObjects(any(), any(), any(), any());
    }

    @Test
    void disabledIs409ForTheObjectsEndpointToo() throws Exception {
        when(properties.enabled()).thenReturn(false);

        mockMvc.perform(post(PURGE_OBJECTS_PATH)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(OBJECTS_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CACHE_PURGE_DISABLED"));

        verify(cachePurgeService, never()).purgeObjects(any(), any(), any(), any());
    }

    private static PurgeReport report() {
        return new PurgeReport(List.of(
                new PurgeOutcome(PurgeTargetKind.URL, PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION,
                        List.of(HOME), "purge-1", "support-1", 5),
                new PurgeOutcome(PurgeTargetKind.TAG, PurgeAction.INVALIDATE, PurgeNetwork.PRODUCTION,
                        List.of("ms-clp-blp"), "purge-2", "support-2", 5)));
    }
}
