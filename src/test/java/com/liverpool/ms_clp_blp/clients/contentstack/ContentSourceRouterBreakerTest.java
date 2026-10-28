package com.liverpool.ms_clp_blp.clients.contentstack;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.FallbackUnavailableReason;
import com.liverpool.ms_clp_blp.clients.resilience.Resilience4jConfig;
import com.liverpool.ms_clp_blp.clients.resilience.ResilienceProperties;
import com.liverpool.ms_clp_blp.shared.errors.ContentstackEntryNotFoundException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the incident that removed this breaker the first time (ADR-034).
 *
 * <p>A breaker on the CMS read was deleted because it aggregated ~13 brands: the ones with nothing
 * published failed on every call, and at a 5% failure-rate threshold that was enough to open the
 * breaker for everybody, LP included. The breaker now guards Contentstack rather than the removed
 * proxy (ADR-053), but the hazard is identical — one client, four stacks, every brand — so the filter
 * is what keeps it safe. These tests run against the <em>real production tuning</em> and assert the
 * exact traffic mix that caused the outage still does not open it.</p>
 *
 * <p>If someone widens the predicate, this is the test that fails.</p>
 *
 * <p>{@link #oneBrandsOutage_opensOnlyThatBrandsBreaker_healthyBrandStaysUnaffected} covers the other
 * guard, added after brands were split into their own breakers (the 2026-09-01 incident): a kind
 * failing on every brand but one must not take a healthy brand down with it.</p>
 */
class ContentSourceRouterBreakerTest {

    /** More than the 20-call minimum, so the breaker has a full say in every scenario. */
    private static final int CALLS = 30;

    private static final ContentSourceRequest REQUEST =
            new ContentSourceRequest("page_blp", "es-mx", "catst26617625", "LP");

    private static final ContentSourceRequest HEALTHY_BRAND_REQUEST =
            new ContentSourceRequest("page_blp", "es-mx", "catst26617625", "SB");

    private static final Map<String, Object> PAYLOAD = Map.of("page_title", "ok");

    private ContentStackDirectGateway gateway;
    private CircuitBreakerRegistry registry;
    private ContentSourceRouter router;

    @BeforeEach
    void setUp() {
        gateway = mock(ContentStackDirectGateway.class);
        when(gateway.unavailableReason(any())).thenReturn(Optional.empty());
        // The deployed tuning, not defaults: 5% failure rate over a 50-call window, 20-call minimum.
        registry = new Resilience4jConfig().circuitBreakerRegistry(new ResilienceProperties(
                5f, 50, 20, Duration.ofSeconds(30), 5));
        router = new ContentSourceRouter(gateway, registry);
    }

    // ── the failures that must NOT open it ───────────────────────────────────────────────────────

    /** The literal incident: a brand with nothing published, failing 100% of the time, forever. */
    @Test
    void unpublishedBrandNotFoundForever_neverOpensTheBreaker() {
        stubNotFound();

        repeatFetch();

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * The 2026-09-02 finding: {@code global_loyalty} exists in only one of four Contentstack stacks,
     * so every other brand 422s on it, forever. That must not open the breaker any more than an
     * unpublished entry does.
     */
    @Test
    void contentTypeRejectedForever_neverOpensTheBreaker() {
        stubContentTypeRejected();

        repeatFetch();

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /**
     * An unhosted brand hammering the endpoint is the caller's bug, not an outage — and it now never
     * even reaches the breaker, because the brand is rejected before any call is attempted.
     */
    @Test
    void unhostedBrandForever_neverOpensTheBreakerAndNeverCallsContentstack() {
        when(gateway.unavailableReason(any()))
                .thenReturn(Optional.of(FallbackUnavailableReason.UNMAPPED_BRAND));

        repeatFetch();

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        verify(gateway, never()).fetchCategoryPage(any(), any(), any(), any());
    }

    /**
     * The precise mix that took production down: overwhelming permanent not-founds with a thin slice
     * of real failures. Unfiltered, the not-founds alone blow through a 5% threshold; filtered, only
     * the 5% counts, which is exactly at the threshold and not beyond it.
     */
    @Test
    void mostlyNotFoundWithAFewRealFailures_staysClosedForTheHealthyBrands() {
        for (int i = 0; i < 40; i++) {
            if (i % 20 == 0) {
                stubOutage();
            } else {
                stubNotFound();
            }
            catchThrowable(() -> router.fetch(REQUEST));
        }

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── the failures that MUST open it ───────────────────────────────────────────────────────────

    @Test
    void contentstackFailing_opensTheBreaker() {
        stubOutage();

        repeatFetch();

        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** The point of opening it: stop paying the timeout on every request, and fail fast to the backup. */
    @Test
    void onceOpen_contentstackIsNoLongerCalled() {
        stubOutage();
        repeatFetch();
        assertThat(breaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBefore = org.mockito.Mockito.mockingDetails(gateway).getInvocations().size();
        catchThrowable(() -> router.fetch(REQUEST));

        assertThat(org.mockito.Mockito.mockingDetails(gateway).getInvocations())
                .hasSize(callsBefore + 1); // the unavailableReason probe only, never a fetch
    }

    // ── the point of splitting by brand ──────────────────────────────────────────────────────────

    /**
     * The 2026-09-01 incident, reproduced: one brand's Contentstack calls fail on every attempt, but a
     * second, healthy brand must still be served rather than rejected as collateral damage.
     */
    @Test
    void oneBrandsOutage_opensOnlyThatBrandsBreaker_healthyBrandStaysUnaffected() {
        doThrow(new IllegalStateException("connection refused"))
                .when(gateway).fetchCategoryPage(any(), any(), any(), eq("LP"));
        doReturn(PAYLOAD).when(gateway).fetchCategoryPage(any(), any(), any(), eq("SB"));

        repeatFetch(); // 30 calls against REQUEST (brand LP) — enough to open LP's own breaker

        assertThat(registry.circuitBreaker(ContentSourceRouter.breakerName("LP")).getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // The incident's exact collateral damage: SB must still be served, not rejected by LP's breaker.
        assertThat(router.fetch(HEALTHY_BRAND_REQUEST)).isEqualTo(PAYLOAD);
        assertThat(registry.circuitBreaker(ContentSourceRouter.breakerName("SB")).getState())
                .isEqualTo(CircuitBreaker.State.CLOSED);
        verify(gateway, atLeastOnce()).fetchCategoryPage(any(), any(), any(), eq("SB"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    /**
     * @return the breaker the router registered for {@link #REQUEST}'s brand
     */
    private CircuitBreaker breaker() {
        return registry.circuitBreaker(ContentSourceRouter.breakerName(REQUEST.brandHeader()));
    }

    /** Drives enough calls for the breaker to have an opinion, ignoring every failure. */
    private void repeatFetch() {
        for (int i = 0; i < CALLS; i++) {
            catchThrowable(() -> router.fetch(REQUEST));
        }
    }

    /** The permanent failure: the stack was reached and holds no such entry. */
    private void stubNotFound() {
        doThrow(new ContentstackEntryNotFoundException("no entry", "detail"))
                .when(gateway).fetchCategoryPage(any(), any(), any(), any());
    }

    /** The other permanent failure: the stack was reached but rejects the content type outright. */
    private void stubContentTypeRejected() {
        doThrow(ContentSourceRouterTest.feignUnprocessableEntity())
                .when(gateway).fetchCategoryPage(any(), any(), any(), any());
    }

    /** The retryable failure: Contentstack could not be reached at all. */
    private void stubOutage() {
        doThrow(new IllegalStateException("connection refused"))
                .when(gateway).fetchCategoryPage(any(), any(), any(), any());
    }
}
