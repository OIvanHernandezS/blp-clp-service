package com.liverpool.ms_clp_blp.shared.session;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import com.liverpool.ms_clp_blp.clients.auth.AuthProperties;
import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionContextAdapterTest {

    private static final String VALID_SESSION_ID = "11111111-2222-3333-4444-555555555555.c2lnbmF0dXJl";

    private static final CmsProperties CONTENT_PROPS = new CmsProperties("LP", "page_blp", "page_clp", "category_redirect");

    private static final AuthProperties AUTH_PROPS = new AuthProperties(
            "http://auth-service", Map.of(), "/v{version}/auth/exchange-token", 2, List.of(2, 3), "SessionId",
            Duration.ofSeconds(2), Duration.ofSeconds(4));

    private MockHttpServletRequest request;
    private AuthTokenExchangePort exchangePort;
    private ListAppender<ILoggingEvent> attachedAppender;
    private Level previousLevel;

    @AfterEach
    void detachLogs() {
        if (attachedAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(SessionContextAdapter.class);
            logger.detachAppender(attachedAppender);
            logger.setLevel(previousLevel);
        }
    }

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        exchangePort = mock(AuthTokenExchangePort.class);
    }

    @Test
    void currentContext_validCookie_exchangesAndPopulatesSession() {
        request.addHeader("x-brand-id", "SB");
        request.addHeader("x-channel", "APP");
        request.addHeader("x-locale", "es-mx");
        request.addHeader("x-user-id", "user-42");
        request.addHeader("authorization", "secret");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(v2(VALID_SESSION_ID, "SB", AuthTenant.APP)))
                .thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        assertThat(session.profileId()).isEqualTo("P1");
        // The bearer the decommission services authenticate with rides along with the identity.
        assertThat(session.authenticatedAccessToken()).isEqualTo("AT1");
        assertThat(session.brand()).isEqualTo("SB");
        assertThat(session.channel()).isEqualTo("APP");
        assertThat(session.headers()).containsEntry("x-user-id", "user-42");
        // Only x-* headers are snapshotted — sensitive/non-x headers never enter the context.
        assertThat(session.headers()).doesNotContainKey("authorization");
        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "SB", AuthTenant.APP));
    }

    @Test
    void currentContext_webChannel_derivesTenantWeb() {
        request.addHeader("x-channel", "WEB");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
    }

    @Test
    void currentContext_appChannel_derivesTenantApp() {
        request.addHeader("x-channel", "app"); // real clients send this, case-insensitive
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.APP));
    }

    // ── channels → auth tenant ──────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"WEB, WEB", "KIOSCO, WEB", "POS, WEB", "APP, APP", "kiosco, WEB", "' Pos ', WEB"})
    void currentContext_definedChannel_exchangesWithItsTenant(String channel, AuthTenant tenant) {
        request.addHeader("x-channel", channel);
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", tenant));
    }

    /**
     * ADR-070: a missing or unrecognised channel never prevents the exchange. It runs with the default
     * tenant ({@code web}, the pre-v0.26.0 behaviour) and the gap is reported at DEBUG. {@code APPS},
     * {@code KIOSK} and {@code MPOS} are spellings that are not the {@code x-channel} contract.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "APPS", "android", "KIOSK", "MPOS"})
    void currentContext_undefinedChannel_stillExchangesWithTheDefaultTenant(String channel) {
        if (!channel.isEmpty()) {
            request.addHeader("x-channel", channel);
        }
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        assertThat(session.authenticatedProfileId()).isEqualTo("P1");
        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
        assertThat(events(logs)).containsExactly(LogEvents.AUTH_CHANNEL_UNDEFINED.name());
    }

    /**
     * The production request that broke on v0.26.0, verbatim in everything that matters: the Android app
     * sends {@code x-client-channel: mobile-android} but no {@code x-channel}, a percent-encoded cookie,
     * and declares itself logged in. It must reach the auth service.
     */
    @Test
    void currentContext_androidAppRequestWithoutXChannel_exchangesOnV2AsWeb() {
        request.addHeader("x-brand-id", "LP");
        request.addHeader("x-client-channel", "mobile-android");
        request.addHeader("x-client-action", "browse");
        request.addHeader("x-client-page", "home");
        request.addHeader("x-is-logged-in", "true");
        request.addHeader("x-profile-id", "3ea0c4ae-2d93-4168-b24b-e9824aaa2d87");
        request.setCookies(new Cookie("SessionId",
                "31cb6700-aaa6-49a5-953d-6378cd9f0d2b.Ic7713eAOEfTHh%2B5WN0%2Fm9EWRep58IegkFQU6AWuH9I"));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        verify(exchangePort).exchange(v2(
                "31cb6700-aaa6-49a5-953d-6378cd9f0d2b.Ic7713eAOEfTHh+5WN0/m9EWRep58IegkFQU6AWuH9I",
                "LP", AuthTenant.WEB));
    }

    /** x-auth-version is the only rule for the version, even when the channel is undefined. */
    @Test
    void currentContext_undefinedChannelWithAuthVersion3_exchangesOnV3AsWeb_withoutAMismatch() {
        request.addHeader("x-auth-version", "3");
        request.addHeader("x-client-platform", "android");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        newAdapter().currentContext();

        verify(exchangePort).exchange(
                new AuthExchangeRequest(VALID_SESSION_ID, "LP", AuthTenant.WEB, 3, "android"));
        // The default tenant is not a statement about the client, so it is not checked against it.
        assertThat(events(logs)).containsExactly(LogEvents.AUTH_CHANNEL_UNDEFINED.name());
    }

    /** The content channel keeps its lenient default; only the auth tenant is resolved separately. */
    @Test
    void currentContext_missingChannel_stillReportsWebAsTheContentChannel() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        assertThat(newAdapter().currentContext().channel()).isEqualTo("WEB");
    }

    // ── x-auth-version / x-client-platform ───────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"3", " 3 "})
    void currentContext_authVersion3_requestsTheV3Exchange(String version) {
        request.addHeader("x-channel", "WEB");
        request.addHeader("x-auth-version", version);
        request.addHeader("x-client-platform", "web");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        newAdapter().currentContext();

        verify(exchangePort).exchange(
                new AuthExchangeRequest(VALID_SESSION_ID, "LP", AuthTenant.WEB, 3, "web"));
    }

    @Test
    void currentContext_authVersion2_isUsedAsSent_withoutAnyEvent() {
        request.addHeader("x-channel", "WEB");
        request.addHeader("x-auth-version", "2");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
        assertThat(events(logs)).isEmpty();
    }

    /** Not a number, or a version the auth service has not published here: the default contract. */
    @ParameterizedTest
    @ValueSource(strings = {"30", "v3", "1", "4", "-3", "3.0"})
    void currentContext_unsupportedAuthVersion_fallsBackToTheDefault(String version) {
        request.addHeader("x-channel", "WEB");
        request.addHeader("x-auth-version", version);
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
        assertThat(events(logs)).containsExactly(LogEvents.AUTH_VERSION_UNSUPPORTED.name());
    }

    /** Releasing a contract is configuration: once listed, a new version is used as sent. */
    @Test
    void currentContext_newlySupportedVersion_isUsedWithoutCodeChanges() {
        request.addHeader("x-channel", "APP");
        request.addHeader("x-auth-version", "4");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        AuthProperties withV4 = new AuthProperties(
                "http://auth-service", Map.of(), "/v{version}/auth/exchange-token", 2, List.of(2, 3, 4),
                "SessionId", Duration.ofSeconds(2), Duration.ofSeconds(4));
        new SessionContextAdapter(request, CONTENT_PROPS, withV4, exchangePort).currentContext();

        verify(exchangePort).exchange(new AuthExchangeRequest(VALID_SESSION_ID, "LP", AuthTenant.APP, 4, null));
    }

    @Test
    void currentContext_clientPlatform_isForwardedTrimmed() {
        request.addHeader("x-channel", "APP");
        request.addHeader("x-auth-version", "3");
        request.addHeader("x-client-platform", " android ");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        newAdapter().currentContext();

        verify(exchangePort).exchange(
                new AuthExchangeRequest(VALID_SESSION_ID, "LP", AuthTenant.APP, 3, "android"));
        assertThat(events(logs)).isEmpty();
    }

    /** Advisory only: the mismatch is reported, and the exchange still runs with the platform as sent. */
    @ParameterizedTest
    @CsvSource({"APP, web, APP", "POS, android, WEB", "WEB, ios, WEB"})
    void currentContext_platformNotMatchingTheChannel_isReportedButStillExchanged(
            String channel, String platform, AuthTenant tenant) {
        request.addHeader("x-channel", channel);
        request.addHeader("x-auth-version", "3");
        request.addHeader("x-client-platform", platform);
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        ListAppender<ILoggingEvent> logs = captureLogs();
        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        verify(exchangePort).exchange(new AuthExchangeRequest(VALID_SESSION_ID, "LP", tenant, 3, platform));
        assertThat(events(logs)).containsExactly(LogEvents.AUTH_PLATFORM_CHANNEL_MISMATCH.name());
    }

    @Test
    void currentContext_missingCookie_returnsGuestWithoutExchange() {
        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isFalse();
        assertThat(session.profileId()).isNull();
        assertThat(session.brand()).isEqualTo("LP");   // defaulted from content-service props
        assertThat(session.channel()).isEqualTo("WEB");
        verify(exchangePort, never()).exchange(any());
    }

    // ── the x-is-logged-in declaration ───────────────────────────────────────────────────────────

    @Test
    void currentContext_declaredGuest_returnsGuestWithoutExchangeEvenWithAValidCookie() {
        // The edge keys its guest slot on this header. If a request that says "false" still produced a
        // signed-in page, that page would be stored in the guest slot and served to everyone.
        request.addHeader(SessionContext.HEADER_IS_LOGGED_IN, "false");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isFalse();
        assertThat(session.declaresGuest()).isTrue();
        verify(exchangePort, never()).exchange(any());
    }

    @Test
    void currentContext_declaredGuest_stillCarriesTheCookieForwardUnchanged() {
        // Skipping the exchange must not blank out sessionId(): it is a separate, local format check,
        // and blocks that forward the cookie keep working exactly as before.
        request.addHeader(SessionContext.HEADER_IS_LOGGED_IN, "false");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        assertThat(newAdapter().currentContext().sessionId()).isEqualTo(VALID_SESSION_ID);
    }

    @Test
    void currentContext_declaredLoggedIn_stillExchanges() {
        request.addHeader("x-channel", "WEB");
        request.addHeader(SessionContext.HEADER_IS_LOGGED_IN, "true");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
    }

    @Test
    void currentContext_noDeclaration_keepsTodaysCookieDrivenBehaviour() {
        request.addHeader("x-channel", "WEB");
        // Callers that omit the header must not be signed out. Only an explicit "false" declares a
        // guest; widening this is the follow-up, once the frontend always sends it.
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        assertThat(session.declaresGuest()).isFalse();
        verify(exchangePort).exchange(v2(VALID_SESSION_ID, "LP", AuthTenant.WEB));
    }

    @Test
    void currentContext_declarationIsCaseInsensitive() {
        request.addHeader(SessionContext.HEADER_IS_LOGGED_IN, "FALSE");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        assertThat(newAdapter().currentContext().authenticated()).isFalse();
        verify(exchangePort, never()).exchange(any());
    }

    @Test
    void currentContext_percentEncodedCookie_decodesBeforeExchanging() {
        request.addHeader("x-channel", "WEB");
        // Browsers store SessionId URL-encoded; the servlet API does not decode it for us.
        request.setCookies(new Cookie("SessionId",
                "237ba270-557d-411f-9985-e946661fe985.UCqTmzC%2Fr8zfVvPyed41N%2BAfoWUCToGTHVn6tEtnglI"));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.authenticated()).isTrue();
        verify(exchangePort).exchange(v2(
                "237ba270-557d-411f-9985-e946661fe985.UCqTmzC/r8zfVvPyed41N+AfoWUCToGTHVn6tEtnglI",
                "LP", AuthTenant.WEB));
    }

    /**
     * The exchange gets the decoded id, but {@link SessionContext#sessionId()} keeps the raw one, so
     * a caller that needs to forward the cookie verbatim still can. Nothing does today — the identity
     * that travels downstream is {@link SessionContext#authenticatedAccessToken()}.
     */
    @Test
    void currentContext_percentEncodedCookie_exposesTheRawValueForForwarding() {
        String raw = "237ba270-557d-411f-9985-e946661fe985.UCqTmzC%2Fr8zfVvPyed41N%2BAfoWUCToGTHVn6tEtnglI";
        request.setCookies(new Cookie("SessionId", raw));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.sessionId()).isEqualTo(raw);
    }

    @Test
    void currentContext_malformedCookie_exposesNoSessionIdToForward() {
        request.setCookies(new Cookie("SessionId", "not-a-valid-session"));

        assertThat(newAdapter().currentContext().sessionId()).isNull();
    }

    @Test
    void currentContext_missingCookie_exposesNoSessionIdToForward() {
        assertThat(newAdapter().currentContext().sessionId()).isNull();
    }

    @Test
    void currentContext_alreadyDecodedCookie_keepsLiteralPlus() {
        request.addHeader("x-channel", "WEB");
        String decoded = "237ba270-557d-411f-9985-e946661fe985.UCqTmzC/r8zfVvPyed41N+AfoWUCToGTHVn6tEtnglI";
        request.setCookies(new Cookie("SessionId", decoded));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        newAdapter().currentContext();

        verify(exchangePort).exchange(v2(decoded, "LP", AuthTenant.WEB));
    }

    @Test
    void currentContext_malformedEscapeSequence_returnsGuestWithoutExchange() {
        request.setCookies(new Cookie("SessionId", "11111111-2222-3333-4444-555555555555.abc%zz"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.isGuest()).isTrue();
        verify(exchangePort, never()).exchange(any());
    }

    @Test
    void currentContext_malformedCookie_returnsGuestWithoutExchange() {
        request.setCookies(new Cookie("SessionId", "not-a-valid-session"));

        SessionContext session = newAdapter().currentContext();

        assertThat(session.isGuest()).isTrue();
        verify(exchangePort, never()).exchange(any());
    }

    @Test
    void currentContext_calledTwice_exchangesOnlyOnce() {
        request.addHeader("x-channel", "WEB");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any())).thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContextAdapter adapter = newAdapter();
        SessionContext first = adapter.currentContext();
        SessionContext second = adapter.currentContext();

        assertThat(second).isSameAs(first);
        verify(exchangePort, times(1)).exchange(any());
    }

    // ── identity-free context (ADR-054) ──────────────────────────────────────────────────────────

    /**
     * The whole point of the second entry point: a valid cookie is present and would exchange
     * successfully, and no call is made anyway.
     */
    @Test
    void currentContextWithoutIdentity_validCookie_neverCallsTheAuthService() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any()))
                .thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        SessionContext session = newAdapter().currentContextWithoutIdentity();

        verify(exchangePort, never()).exchange(any());
        assertThat(session.authenticated()).isFalse();
        assertThat(session.profileId()).isNull();
        assertThat(session.accessToken()).isNull();
    }

    /**
     * Everything that does not come from the exchange must still be there — otherwise the endpoints
     * that opted out would lose brand/locale routing and the ADR-041 Salesforce gate.
     */
    @Test
    void currentContextWithoutIdentity_populatesEverythingTheExchangeDoesNotProduce() {
        request.addHeader("x-brand-id", "SB");
        request.addHeader("x-channel", "APPS");
        request.addHeader("x-locale", "en-us");
        request.addHeader("x-sf-user-id", "atg-9");
        request.addHeader("authorization", "secret");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));

        SessionContext session = newAdapter().currentContextWithoutIdentity();

        assertThat(session.brand()).isEqualTo("SB");
        assertThat(session.channel()).isEqualTo("APPS");
        assertThat(session.locale()).isEqualTo("en-us");
        assertThat(session.header("x-sf-user-id")).isEqualTo("atg-9");
        assertThat(session.headers()).doesNotContainKey("authorization");
        // The cookie is format-checked locally, so the Salesforce V1 gate survives without the exchange.
        assertThat(session.sessionId()).isEqualTo(VALID_SESSION_ID);
        verify(exchangePort, never()).exchange(any());
    }

    /** A malformed cookie is rejected here exactly as it is on the identity path. */
    @Test
    void currentContextWithoutIdentity_malformedCookie_exposesNoSessionIdToForward() {
        request.setCookies(new Cookie("SessionId", "not-a-session-id"));

        assertThat(newAdapter().currentContextWithoutIdentity().sessionId()).isNull();
        verify(exchangePort, never()).exchange(any());
    }

    @Test
    void currentContextWithoutIdentity_calledTwice_returnsTheSameInstance() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        SessionContextAdapter adapter = newAdapter();

        SessionContext first = adapter.currentContextWithoutIdentity();

        assertThat(adapter.currentContextWithoutIdentity()).isSameAs(first);
        verify(exchangePort, never()).exchange(any());
    }

    /**
     * Memo consistency, the direction that matters: once the exchange has been paid for, the
     * identity-free method must hand back the real context rather than a guest-shaped copy of it.
     * Returning the downgraded one would make a caller's answer depend on which controller ran first.
     */
    @Test
    void currentContextWithoutIdentity_afterCurrentContext_reusesTheExchangedContext() {
        request.addHeader("x-channel", "WEB");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any()))
                .thenReturn(new AuthExchangeResult(true, "P1", "AT1"));
        SessionContextAdapter adapter = newAdapter();

        SessionContext exchanged = adapter.currentContext();

        assertThat(adapter.currentContextWithoutIdentity()).isSameAs(exchanged);
        verify(exchangePort, times(1)).exchange(any());
    }

    /** The reverse ordering still exchanges — the light memo carries no identity to reuse. */
    @Test
    void currentContext_afterCurrentContextWithoutIdentity_stillExchanges() {
        request.addHeader("x-channel", "WEB");
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any()))
                .thenReturn(new AuthExchangeResult(true, "P1", "AT1"));
        SessionContextAdapter adapter = newAdapter();

        adapter.currentContextWithoutIdentity();
        SessionContext session = adapter.currentContext();

        assertThat(session.authenticated()).isTrue();
        assertThat(session.authenticatedProfileId()).isEqualTo("P1");
        verify(exchangePort, times(1)).exchange(any());
    }

    // ── A/B cohort markers ───────────────────────────────────────────────────────────────────────

    /** A rule may name any header, so the marker snapshot is not limited to {@code x-*}. */
    @Test
    void currentContextWithoutIdentity_capturesReadableHeaderMarkersOfAnyName() {
        request.addHeader("X-AB-Carousel", " TRUE ");
        request.addHeader("ab-variant", "false");
        request.addHeader("x-brand-id", "LP");

        CohortMarkers markers = newAdapter().currentContextWithoutIdentity().cohortMarkers();

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
    void currentContextWithoutIdentity_capturesOnlyReadableCookieMarkers() {
        request.addHeader("authorization", "Bearer secret");
        request.setCookies(
                new Cookie("SessionId", VALID_SESSION_ID),
                new Cookie("ab_carousel", "false"),
                new Cookie("ab_other", "1"));

        CohortMarkers markers = newAdapter().currentContextWithoutIdentity().cohortMarkers();

        assertThat(markers.cookies()).containsOnly(Map.entry("ab_carousel", false));
        assertThat(markers.headers()).isEmpty();
    }

    /** Commons stops at the first cookie of a name, so a later readable duplicate must not win. */
    @Test
    void currentContextWithoutIdentity_repeatedCookie_onlyTheFirstCounts() {
        request.setCookies(
                new Cookie("ab_carousel", "junk"),
                new Cookie("ab_carousel", "true"),
                new Cookie("ab_second", "true"),
                new Cookie("ab_second", "false"));

        CohortMarkers markers = newAdapter().currentContextWithoutIdentity().cohortMarkers();

        assertThat(markers.cookie("ab_carousel")).isNull();
        assertThat(markers.cookie("ab_second")).isTrue();
    }

    @Test
    void currentContext_noMarkers_carriesNone() {
        request.setCookies(new Cookie("SessionId", VALID_SESSION_ID));
        when(exchangePort.exchange(any()))
                .thenReturn(new AuthExchangeResult(true, "P1", "AT1"));

        assertThat(newAdapter().currentContext().cohortMarkers()).isEqualTo(CohortMarkers.NONE);
    }

    private static AuthExchangeRequest v2(String sessionId, String brand, AuthTenant tenant) {
        return new AuthExchangeRequest(sessionId, brand, tenant, 2, null);
    }

    private ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(SessionContextAdapter.class);
        // The channel/version events are DEBUG; without this they would be NOP and never captured.
        previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        attachedAppender = appender;
        return appender;
    }

    private static List<String> events(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .flatMap(e -> e.getKeyValuePairs() == null ? Stream.empty() : e.getKeyValuePairs().stream())
                .filter(kv -> LogFields.EVENT.equals(kv.key))
                .map(kv -> String.valueOf(kv.value))
                .toList();
    }

    private SessionContextAdapter newAdapter() {
        return new SessionContextAdapter(request, CONTENT_PROPS, AUTH_PROPS, exchangePort);
    }
}
