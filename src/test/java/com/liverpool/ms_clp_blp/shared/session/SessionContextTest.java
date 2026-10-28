package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionContext}, focused on {@link SessionContext#authenticatedProfileId()}
 * — the guard ADR-033 introduced so a guest's anonymous id never leaks into a personalisation call
 * that expects a real identity.
 */
class SessionContextTest {

    /** Non-empty, so a copy that dropped the component would fail the recursive comparison. */
    private static final CohortMarkers MARKERS =
            new CohortMarkers(Map.of("x-ab-carousel", true), Map.of("ab_carousel", false));

    @Test
    void authenticatedProfileId_authenticatedSession_returnsProfileId() {
        SessionContext session = new SessionContext(true, "LP", "WEB", "es-mx", "31216912118", Map.of());

        assertThat(session.authenticatedProfileId()).isEqualTo("31216912118");
    }

    /**
     * The regression this test guards: {@code AuthTokenExchangeAdapter} sets {@code profileId} to
     * the anonymous id for a guest with a successfully-exchanged anonymous token — {@code
     * profileId()} alone is non-null there, so a naive caller reading it directly would send a
     * guest's anonymous id to Salesforce as if it were a real ATG identity.
     */
    @Test
    void authenticatedProfileId_guestSessionWithAnonymousProfileId_returnsNull() {
        SessionContext session = new SessionContext(false, "LP", "WEB", "es-mx", "anon-abc123", Map.of());

        assertThat(session.authenticatedProfileId()).isNull();
    }

    @Test
    void authenticatedProfileId_guestSessionWithNullProfileId_returnsNull() {
        SessionContext session = new SessionContext(false, "LP", "WEB", "es-mx");

        assertThat(session.authenticatedProfileId()).isNull();
    }

    @Test
    void isGuest_authenticatedSession_returnsFalse() {
        SessionContext session = new SessionContext(true, "LP", "WEB", "es-mx");

        assertThat(session.isGuest()).isFalse();
    }

    @Test
    void isGuest_unauthenticatedSession_returnsTrue() {
        SessionContext session = new SessionContext(false, "LP", "WEB", "es-mx");

        assertThat(session.isGuest()).isTrue();
    }

    /**
     * Regression: when {@code GET /blp|clp/**} still resolved brand from a query param it rebuilt the record
     * with a constructor, which dropped {@code sessionId} the moment it was added — the shortcuts
     * service then saw no cookie and was never called. Every component but {@code brand} must survive
     * the copy, so that the next caller that needs a re-branded context cannot repeat it.
     */
    @Test
    void withBrand_replacesBrandAndCarriesEveryOtherComponent() {
        SessionContext session = new SessionContext(true, "LP", "APPS", "es-mx", "prn-1",
                Map.of("x-jml-user-id", "jml-1"), "sid.raw%2Bvalue", "token-1", MARKERS);

        SessionContext copy = session.withBrand("SB");

        assertThat(copy.brand()).isEqualTo("SB");
        assertThat(copy).usingRecursiveComparison().ignoringFields("brand").isEqualTo(session);
    }

    /**
     * {@code GET /blp|clp/**} never calls the auth service (ADR-067): it folds the caller's {@code x-is-logged-in}
     * declaration into an otherwise guest-shaped context via this method rather than reconstructing
     * the record by hand — same rationale as {@link #withBrand_replacesBrandAndCarriesEveryOtherComponent()}.
     */
    @Test
    void withAuthenticated_replacesAuthenticatedAndCarriesEveryOtherComponent() {
        SessionContext session = new SessionContext(false, "LP", "APPS", "es-mx", "prn-1",
                Map.of("x-jml-user-id", "jml-1"), "sid.raw%2Bvalue", "token-1", MARKERS);

        SessionContext copy = session.withAuthenticated(true);

        assertThat(copy.authenticated()).isTrue();
        assertThat(copy).usingRecursiveComparison().ignoringFields("authenticated").isEqualTo(session);
    }

    @Test
    void header_lookupIsCaseInsensitive() {
        SessionContext session = new SessionContext(true, "LP", "WEB", "es-mx", null,
                Map.of("x-jml-user-id", "jml-1"));

        assertThat(session.header("X-JML-USER-ID")).isEqualTo("jml-1");
    }
}
