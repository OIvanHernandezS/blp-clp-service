package com.liverpool.ms_clp_blp.clients.contentstack;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.FallbackUnavailableReason;
import com.liverpool.ms_clp_blp.shared.errors.BrandNotSupportedException;
import com.liverpool.ms_clp_blp.shared.errors.ContentServiceUnavailableException;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;
import com.liverpool.ms_clp_blp.shared.web.CmsResponseMetadataContext;
import com.liverpool.ms_clp_blp.shared.web.ContentOrigin;

import feign.FeignException;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What Contentstack's answer — or its failure — looks like to the caller.
 *
 * <p>Contentstack is the only live source since ADR-053, so the routing question this class used to
 * answer ("proxy or fallback?") is gone. What survives is the half that still matters: the failure
 * contract is unchanged, because removing a source must not change what {@code GET /blp|clp/**} returns.
 * A failure the <em>caller</em> is responsible for keeps its own status code rather than being
 * flattened into an outage.</p>
 */
class ContentSourceRouterTest {

    private static final ContentSourceRequest PAGE_REQUEST =
            new ContentSourceRequest("page_blp", "es-mx", "catst26617625", "LP");

    private static final Map<String, Object> PAYLOAD = Map.of("title", "from contentstack");

    private ContentStackDirectGateway gateway;
    private ContentSourceRouter router;

    @BeforeEach
    void setUp() {
        gateway = ContentSourceRouterFixtures.servableGateway();
        router = ContentSourceRouterFixtures.routerOver(gateway);
    }

    @AfterEach
    void clearOriginContext() {
        CmsResponseMetadataContext.clear();
    }

    // ── the happy path ───────────────────────────────────────────────────────────────────────────

    @Test
    void fetch_pageRequest_readsContentstackDirectly() {
        doReturn(PAYLOAD).when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThat(router.fetch(PAGE_REQUEST)).isEqualTo(PAYLOAD);
    }

    @Test
    void fetch_served_recordsContentstackOrigin() {
        doReturn(PAYLOAD).when(gateway).fetchCategoryPage(any(), any(), any(), any());

        router.fetch(PAGE_REQUEST);

        assertThat(CmsResponseMetadataContext.current()).isEqualTo(ContentOrigin.CONTENTSTACK);
    }

    // ── failures the caller owns ─────────────────────────────────────────────────────────────────

    /**
     * A brand no stack hosts kept the 400 the proxy used to answer with. Nothing is fetched: asking
     * Contentstack for a brand it does not host wastes a call to arrive at the same answer.
     */
    @Test
    void fetch_unmappedBrand_is400AndCallsNothing() {
        ContentSourceRouterFixtures.unservable(gateway, FallbackUnavailableReason.UNMAPPED_BRAND);

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(BrandNotSupportedException.class);
        verify(gateway, never()).fetchCategoryPage(any(), any(), any(), any());
    }

    @Test
    void fetch_entryNotPublished_propagatesTheNotFound() {
        doThrow(new ContentstackEntryNotFoundException("no entry", "detail"))
                .when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentstackEntryNotFoundException.class);
    }

    /**
     * A content type this brand's stack was never given (e.g. {@code global_loyalty} for every brand
     * but LP) answers with a 422, not an empty {@code entries} array — but it is the same permanent
     * fact, so it must reach the caller as the same not-found rather than an outage.
     */
    @Test
    void fetch_contentTypeRejected_becomesEntryNotFound() {
        doThrow(feignUnprocessableEntity()).when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentstackEntryNotFoundException.class);
    }

    // ── failures the service owns ────────────────────────────────────────────────────────────────

    /**
     * Missing credentials are a deployment fault, not a caller error, so this is the 502 an operator
     * can act on rather than the 400 an unmapped brand gets.
     */
    @Test
    void fetch_missingCredentials_is502AndCallsNothing() {
        ContentSourceRouterFixtures.unservable(gateway, FallbackUnavailableReason.MISSING_CREDENTIALS);

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentServiceUnavailableException.class);
        verify(gateway, never()).fetchCategoryPage(any(), any(), any(), any());
    }

    @Test
    void fetch_transportFailure_becomesUnavailable() {
        doThrow(new IllegalStateException("connection refused"))
                .when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentServiceUnavailableException.class);
    }

    @Test
    void fetch_nullBody_becomesUnavailableRatherThanAnNpeDownstream() {
        doReturn(null).when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentServiceUnavailableException.class);
    }

    /** A failed read must not leave a previous request's origin standing. */
    @Test
    void fetch_failed_recordsNoOrigin() {
        doThrow(new IllegalStateException("down")).when(gateway).fetchCategoryPage(any(), any(), any(), any());

        assertThatThrownBy(() -> router.fetch(PAGE_REQUEST))
                .isInstanceOf(ContentServiceUnavailableException.class);
        assertThat(CmsResponseMetadataContext.current()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /** A 422 the way Contentstack actually sends one for a content type this stack lacks. */
    static FeignException feignUnprocessableEntity() {
        return FeignException.errorStatus("ContentStackClient#getEntriesByQueryWithIncludes()",
                Response.builder()
                        .status(422)
                        .reason("Unprocessable Entity")
                        .request(Request.create(Request.HttpMethod.GET, "/entries", Map.of(),
                                new byte[0], StandardCharsets.UTF_8, new RequestTemplate()))
                        .headers(Map.of())
                        .body("{\"error_message\":\"The Content Type 'global_loyalty' was not found. "
                                        + "Please try again.\",\"error_code\":118}",
                                StandardCharsets.UTF_8)
                        .build());
    }
}
