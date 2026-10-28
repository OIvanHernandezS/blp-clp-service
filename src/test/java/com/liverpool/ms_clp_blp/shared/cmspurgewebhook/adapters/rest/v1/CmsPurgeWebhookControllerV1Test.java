package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.adapters.rest.v1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.ports.CmsPurgeWebhookService;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the CMS-triggered purge callback.
 *
 * <p>As with the brand-flags webhook, nothing here asserts who is allowed in: the endpoint carries no
 * credential of its own and is guarded by the service-wide HTTP Basic credential, which is
 * {@code SecurityConfigTest}'s contract. {@code @WithServiceCredential} satisfies that chain so these
 * cases can exercise what is left — that every outcome, including a failed purge, is a 200.</p>
 */
@WebMvcTest(CmsPurgeWebhookControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
@WithServiceCredential
class CmsPurgeWebhookControllerV1Test {

    private static final String PATH = "/webhooks/contentstack/cache-purge";
    private static final String BODY = """
            {"event":"publish","data":{"content_type":{"uid":"flex"},
             "entry":{"uid":"blt2f716c7fdc5e71be","locale":"es-mx"}}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CmsPurgeWebhookService purgeWebhookService;

    @Test
    void handsTheDeliveryToTheServiceAndReportsThePurgeItCaused() throws Exception {
        doReturn(CmsPurgeWebhookResult.purged("flex", "publish", PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, 3))
                .when(purgeWebhookService).apply(any());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(true))
                .andExpect(jsonPath("$.contentType").value("flex"))
                .andExpect(jsonPath("$.event").value("publish"))
                .andExpect(jsonPath("$.action").value("invalidate"))
                .andExpect(jsonPath("$.network").value("production"))
                .andExpect(jsonPath("$.objectCount").value(3))
                .andExpect(header().string("x-request-id", notNullValue()));

        verify(purgeWebhookService).apply(any());
    }

    /**
     * The retry-loop guard: an ignored delivery is still a 200, because Contentstack retries every
     * non-2xx and a stack that notifies us about every content type would otherwise never stop.
     */
    @Test
    void answersOkEvenWhenNothingWasPurged() throws Exception {
        doReturn(CmsPurgeWebhookResult.ignored("ms_clp_blp_flags", "publish",
                "Content type 'ms_clp_blp_flags' does not front any cached ms-clp-blp endpoint"))
                .when(purgeWebhookService).apply(any());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(false))
                .andExpect(jsonPath("$.objectCount").value(0))
                .andExpect(jsonPath("$.action").value(nullValue()))
                .andExpect(jsonPath("$.network").value(nullValue()));
    }

    /**
     * The same applies to a purge Akamai refused. Reporting it as a 5xx would make Contentstack retry
     * a delivery that a retry cannot fix — the credential is still wrong on the next attempt.
     */
    @Test
    void answersOkEvenWhenTheCdnRejectedThePurge() throws Exception {
        doReturn(CmsPurgeWebhookResult.failed("page", "publish", PurgeAction.INVALIDATE,
                PurgeNetwork.PRODUCTION, "The CDN rejected the purge credential."))
                .when(purgeWebhookService).apply(any());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(false))
                .andExpect(jsonPath("$.reason").value("The CDN rejected the purge credential."));
    }

    /** An empty body is a delivery like any other; the service decides it is not actionable. */
    @Test
    void toleratesAnAbsentBody() throws Exception {
        doReturn(CmsPurgeWebhookResult.ignored(null, null, "Delivery carried no content type"))
                .when(purgeWebhookService).apply(any());

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purged").value(false));
    }
}
