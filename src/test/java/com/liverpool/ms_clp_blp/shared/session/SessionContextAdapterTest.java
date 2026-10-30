package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;

import static org.assertj.core.api.Assertions.assertThat;

class SessionContextAdapterTest {

    private static final String VALID_SESSION_ID = "11111111-2222-3333-4444-555555555555.c2lnbmF0dXJl";

    private static final CmsProperties CONTENT_PROPS = new CmsProperties("LP", "page_blp", "page_clp", "category_redirect");

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
    }

    @Test
    void currentContext_populatesEverythingFromTheRequest() {
        request.addHeader("x-brand-id", "SB");
        request.addHeader("x-channel", "APPS");
        request.addHeader("x-locale", "en-us");
        request.addHeader("x-sf-user-id", "atg-9");
        request.addHeader("authorization", "secret");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.brand()).isEqualTo("SB");
        assertThat(session.channel()).isEqualTo("APPS");
        assertThat(session.locale()).isEqualTo("en-us");
        assertThat(session.header("x-sf-user-id")).isEqualTo("atg-9");
        // Only x-* headers are snapshotted — sensitive/non-x headers never enter the context.
        assertThat(session.headers()).doesNotContainKey("authorization");
        // The cookie is format-checked locally, which is all the Salesforce V1 gate needs.
        assertThat(session.sessionId()).isEqualTo(VALID_SESSION_ID);
    }

    /** The login signal is the caller's declaration, folded in by the page endpoint (ADR-067). */
    @Test
    void currentContext_isGuestShapedEvenWithAValidCookie() {
        request.addHeader(SessionContext.HEADER_IS_LOGGED_IN, "true");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isFalse();
        assertThat(session.declaresLoggedIn()).isTrue();
    }

    @Test
    void currentContext_missingHeaders_fallBackToDefaults() {
        SessionContext session = newAdapter().currentContext();

        assertThat(session.brand()).isEqualTo("LP");
        assertThat(session.channel()).isEqualTo("WEB");
        assertThat(session.locale()).isEqualTo("es-mx");
    }

    // ── SessionId cookie ─────────────────────────────────────────────────────────────────────────

    /** The web app writes the cookie URL-encoded; it must pass the format check yet travel raw. */
    @Test
    void currentContext_percentEncodedCookie_isAcceptedAndKeptRaw() {
        String raw = "237ba270-557d-411f-9985-e946661fe985.UCqTmzC%2Fr8zfVvPyed41N%2BAfoWUCToGTHVn6tEtnglI";
        request.setCookies(new Cookie("SessionId", raw));

        assertThat(newAdapter().currentContext().sessionId()).isEqualTo(raw);
    }

    /** {@code '+'} is a valid base64 character; it must not be decoded into a space. */
    @Test
    void currentContext_alreadyDecodedCookie_keepsLiteralPlus() {
        String decoded = "237ba270-557d-411f-9985-e946661fe985.UCqTmzC/r8zfVvPyed41N+AfoWUCToGTHVn6tEtnglI";
        request.setCookies(new Cookie("SessionId", decoded));

        assertThat(newAdapter().currentContext().sessionId()).isEqualTo(decoded);
    }

    @Test
    void currentContext_malformedCookie_exposesNoSessionId() {
        request.setCookies(new Cookie("SessionId", "not-a-valid-session"));

        assertThat(newAdapter().currentContext().sessionId()).isNull();
    }

    @Test
    void currentContext_malformedEscapeSequence_exposesNoSessionId() {
        request.setCookies(new Cookie("SessionId", "11111111-2222-3333-4444-555555555555.%ZZ"));

        assertThat(newAdapter().currentContext().sessionId()).isNull();
    }

    @Test
    void currentContext_missingCookie_exposesNoSessionId() {
        assertThat(newAdapter().currentContext().sessionId()).isNull();
    }

    @Test
    void currentContext_calledTwice_returnsTheSameInstance() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        SessionContextAdapter adapter = newAdapter();

        SessionContext first = adapter.currentContext();

        assertThat(adapter.currentContext()).isSameAs(first);
    }

    // ── A/B cohort markers ───────────────────────────────────────────────────────────────────────

    /** A rule may name any header, so the marker snapshot is not limited to {@code x-*}. */
    @Test
    void currentContext_capturesReadableHeaderMarkersOfAnyName() {
        request.addHeader("X-AB-Carousel", " TRUE ");
        request.addHeader("ab-variant", "false");
        request.addHeader("x-brand-id", "LP");

        CohortMarkers markers = newAdapter().currentContext().cohortMarkers();

        assertThat(markers.header("x-ab-carousel")).isTrue();
        assertThat(markers.header("ab-variant")).isFalse();
        // Not true/false, so indistinguishable from not sent — and never held in the snapshot.
        assertThat(markers.headers()).containsOnlyKeys("x-ab-carousel", "ab-variant");
    }

    /**
     * Only the literal words are kept, which is what keeps secrets out: the session cookie and a bearer
     * token sit on the same request and never enter the snapshot.
     */
    @Test
    void currentContext_capturesOnlyReadableCookieMarkers() {
        request.addHeader("authorization", "Bearer secret");
        request.setCookies(
                new Cookie("SessionId", VALID_SESSION_ID),
                new Cookie("ab_carousel", "false"),
                new Cookie("ab_other", "1"));

        CohortMarkers markers = newAdapter().currentContext().cohortMarkers();

        assertThat(markers.cookies()).containsOnly(Map.entry("ab_carousel", false));
        assertThat(markers.headers()).isEmpty();
    }

    /** Commons stops at the first cookie of a name, so a later readable duplicate must not win. */
    @Test
    void currentContext_repeatedCookie_onlyTheFirstCounts() {
        request.setCookies(
                new Cookie("ab_carousel", "junk"),
                new Cookie("ab_carousel", "true"),
                new Cookie("ab_second", "true"),
                new Cookie("ab_second", "false"));

        CohortMarkers markers = newAdapter().currentContext().cohortMarkers();

        assertThat(markers.cookie("ab_carousel")).isNull();
        assertThat(markers.cookie("ab_second")).isTrue();
    }

    @Test
    void currentContext_noMarkers_carriesNone() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        assertThat(newAdapter().currentContext().cohortMarkers()).isEqualTo(CohortMarkers.NONE);
    }

    private SessionContextAdapter newAdapter() {
        return new SessionContextAdapter(request, CONTENT_PROPS);
    }
}
