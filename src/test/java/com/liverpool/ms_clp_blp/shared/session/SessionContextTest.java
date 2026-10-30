package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionContext}: the login/guest accessors, the copy-with helpers and the
 * header lookup.
 */
class SessionContextTest {

    /** Non-empty, so a copy that dropped the component would fail the recursive comparison. */
    private static final CohortMarkers MARKERS =
            new CohortMarkers(Map.of("x-ab-carousel", true), Map.of("ab_carousel", false));

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
     * with a constructor, which dropped {@code sessionId} the moment it was added. Every component
     * but {@code brand} must survive
     * the copy, so that the next caller that needs a re-branded context cannot repeat it.
     */
    @Test
    void withBrand_replacesBrandAndCarriesEveryOtherComponent() {
        SessionContext session = new SessionContext(true, "LP", "APPS", "es-mx",
                Map.of("x-jml-user-id", "jml-1"), "sid.raw%2Bvalue", MARKERS);

        SessionContext copy = session.withBrand("SB");

        assertThat(copy.brand()).isEqualTo("SB");
        assertThat(copy).usingRecursiveComparison().ignoringFields("brand").isEqualTo(session);
    }

    /**
     * {@code GET /blp|clp/**} (ADR-067) folds the caller's {@code x-is-logged-in} declaration into an
     * otherwise guest-shaped context via this method rather than reconstructing the record by hand — same rationale as {@link #withBrand_replacesBrandAndCarriesEveryOtherComponent()}.
     */
    @Test
    void withAuthenticated_replacesAuthenticatedAndCarriesEveryOtherComponent() {
        SessionContext session = new SessionContext(false, "LP", "APPS", "es-mx",
                Map.of("x-jml-user-id", "jml-1"), "sid.raw%2Bvalue", MARKERS);

        SessionContext copy = session.withAuthenticated(true);

        assertThat(copy.authenticated()).isTrue();
        assertThat(copy).usingRecursiveComparison().ignoringFields("authenticated").isEqualTo(session);
    }

    @Test
    void header_lookupIsCaseInsensitive() {
        SessionContext session = new SessionContext(true, "LP", "WEB", "es-mx",
                Map.of("x-jml-user-id", "jml-1"), null);

        assertThat(session.header("X-JML-USER-ID")).isEqualTo("jml-1");
    }
}
