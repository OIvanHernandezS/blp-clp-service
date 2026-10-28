package com.liverpool.ms_clp_blp.landing.brandflags.adapters.rest.v1;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsSyncService;
import com.liverpool.ms_clp_blp.shared.web.MdcRequestContextFilter;
import com.liverpool.ms_clp_blp.shared.web.SecurityConfig;
import com.liverpool.ms_clp_blp.shared.web.WithServiceCredential;

import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for the Contentstack callback.
 *
 * <p><strong>Nothing here asserts authentication, and that is the point.</strong> The webhook carries
 * no credential of its own: it is guarded by the service-wide inbound HTTP Basic credential that
 * {@code SecurityConfig} applies to every endpoint, so who is allowed in is {@code SecurityConfigTest}'s
 * contract, not this one's. {@code @WithServiceCredential} simply satisfies that chain so the cases
 * below can exercise what is actually left here — which delivery reaches the use case, and what status
 * an irrelevant one gets.</p>
 */
@WebMvcTest(ContentstackWebhookControllerV1.class)
@Import({SecurityConfig.class, MdcRequestContextFilter.class})
@WithServiceCredential
class ContentstackWebhookControllerV1Test {

    private static final String PATH = "/webhooks/contentstack";
    private static final String BODY = """
            {"event":"publish","data":{"content_type":{"uid":"ms_clp_blp_flags"}}}""";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BrandFlagsSyncService syncService;

    @Test
    void handsTheDeliveryToTheSyncServiceAndReportsWhatItDid() throws Exception {
        doReturn(new BrandFlagsSyncResult(true, "upsert", "GAP", "Stored 2 flag override(s)"))
                .when(syncService).apply(anyString(), any());

        mockMvc.perform(post(PATH)
                        .header("x-brand-id", "GAP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.action").value("upsert"))
                .andExpect(jsonPath("$.brand").value("GAP"))
                .andExpect(jsonPath("$.reason").value("Stored 2 flag override(s)"))
                .andExpect(header().string("x-request-id", notNullValue()));

        verify(syncService).apply(eq("GAP"), any());
    }

    /**
     * The retry-loop guard: an ignored delivery is still a 200, because Contentstack retries every
     * non-2xx and a stack that notifies us about everything would otherwise never stop.
     */
    @Test
    void answersOkEvenWhenNothingWasApplied() throws Exception {
        doReturn(BrandFlagsSyncResult.ignored("GAP", "Content type 'page' is not the flag entry"))
                .when(syncService).apply(anyString(), any());

        mockMvc.perform(post(PATH)
                        .header("x-brand-id", "GAP")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.action").value("ignored"))
                .andExpect(jsonPath("$.reason").value("Content type 'page' is not the flag entry"));
    }

    /** An empty body is a delivery like any other; the service decides it is not actionable. */
    @Test
    void toleratesAnAbsentBody() throws Exception {
        doReturn(BrandFlagsSyncResult.ignored("GAP", "no content type"))
                .when(syncService).apply(anyString(), any());

        mockMvc.perform(post(PATH)
                        .header("x-brand-id", "GAP")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /**
     * A missing brand header is a stack misconfiguration, not a transport error. It reaches the
     * service, which reports it — rejecting it with a 400 would make Contentstack retry the same
     * broken delivery forever.
     */
    @Test
    void passesAMissingBrandThroughToTheService() throws Exception {
        doReturn(BrandFlagsSyncResult.ignored(null, "Delivery carried no brand; x-brand-id is required"))
                .when(syncService).apply(any(), any());

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false));

        verify(syncService).apply(eq(null), any());
    }
}
