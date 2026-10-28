package com.liverpool.ms_clp_blp.clients.contentstack;

import java.util.Optional;

import com.liverpool.ms_clp_blp.clients.contentstackdirect.ContentStackDirectGateway;
import com.liverpool.ms_clp_blp.clients.contentstackdirect.FallbackUnavailableReason;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared construction of {@link ContentSourceRouter} for the adapter tests.
 *
 * <p>The adapter tests are about mapping a CMS payload onto a domain model, not about how it was
 * fetched, so they drive a mocked {@link ContentStackDirectGateway} through a real router. Routing and
 * breaker behaviour are covered separately by {@code ContentSourceRouterTest} and
 * {@code ContentSourceRouterBreakerTest}.</p>
 */
public final class ContentSourceRouterFixtures {

    private ContentSourceRouterFixtures() {
    }

    /**
     * @return a gateway mock whose brand is servable, so tests stub only the fetch they care about
     */
    public static ContentStackDirectGateway servableGateway() {
        ContentStackDirectGateway gateway = mock(ContentStackDirectGateway.class);
        when(gateway.unavailableReason(any())).thenReturn(Optional.empty());
        return gateway;
    }

    /**
     * @param gateway the mocked Contentstack gateway the test drives
     * @return a router over it, with a breaker that cannot open during a handful of calls — these
     *         tests measure mapping, not breaker behaviour
     */
    public static ContentSourceRouter routerOver(ContentStackDirectGateway gateway) {
        return new ContentSourceRouter(gateway, CircuitBreakerRegistry.ofDefaults());
    }

    /**
     * Makes the gateway report a brand it cannot serve at all.
     *
     * @param gateway the mocked gateway
     * @param reason  why the brand cannot be served
     */
    public static void unservable(ContentStackDirectGateway gateway, FallbackUnavailableReason reason) {
        when(gateway.unavailableReason(any())).thenReturn(Optional.of(reason));
    }
}
