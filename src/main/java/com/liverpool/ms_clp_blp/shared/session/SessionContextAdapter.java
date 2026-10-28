package com.liverpool.ms_clp_blp.shared.session;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import com.liverpool.ms_clp_blp.clients.auth.AuthProperties;
import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Request-scoped adapter that builds the {@link SessionContext} for the in-flight HTTP request.
 *
 * <p>It resolves the login/guest signal and profile id by an active token exchange: read the
 * {@code SessionId} cookie, validate its opaque format, and call the auth microservice via
 * {@link AuthTokenExchangePort} (ported from the digital_bff auth flow — ms-clp-blp no longer trusts an
 * {@code x-authenticated} header). Brand/channel/locale still come from upstream headers, and every
 * {@code x-*} header is snapshotted so blocks can read their own custom headers via
 * {@link SessionContext#header(String)}.</p>
 *
 * <p>The exchange is only paid for by callers that consume its result:
 * {@link #currentContextWithoutIdentity()} resolves everything else — brand, channel, locale, the
 * header snapshot, and the format-validated cookie — with no outbound call at all. Both entry points
 * memoize independently for the life of the request.</p>
 *
 * <h3>Expected request inputs:</h3>
 * <ul>
 *   <li>{@code SessionId} cookie — opaque session id exchanged for login/guest + profile id</li>
 *   <li>{@code x-is-logged-in} — the caller's own login declaration; an explicit {@code false} skips
 *       the exchange and yields a guest outright (see {@link SessionContext#declaresGuest()})</li>
 *   <li>{@code x-brand-id} — brand identifier; falls back to {@code content-service.default-brand}</li>
 *   <li>{@code x-channel} — originating channel ({@code WEB}/{@code APP}/{@code KIOSCO}/{@code POS});
 *       drives the auth {@code tenant} ({@code WEB/KIOSCO/POS→web}, {@code APP→app}) — see
 *       {@link AuthTenant}. A missing or unrecognised channel never prevents the exchange: it runs
 *       with tenant {@code web} ({@code AUTH_CHANNEL_UNDEFINED}, DEBUG; ADR-070)</li>
 *   <li>{@code x-auth-version} — the only input that picks the auth contract: a supported version
 *       ({@code AUTH_SUPPORTED_VERSIONS}, default {@code 2,3}) is used as sent; absent or anything
 *       else falls back to {@code AUTH_DEFAULT_VERSION} ({@code 2})</li>
 *   <li>{@code x-client-platform} — {@code web}/{@code android}/{@code ios}, forwarded to the auth
 *       service only</li>
 *   <li>{@code x-locale} — content locale (e.g. {@code es-mx})</li>
 * </ul>
 */
@Component
@RequestScope
public class SessionContextAdapter implements SessionContextPort {

    static final String HEADER_BRAND_ID = "x-brand-id";
    static final String HEADER_CHANNEL = "x-channel";
    static final String HEADER_LOCALE = "x-locale";
    static final String HEADER_AUTH_VERSION = "x-auth-version";
    static final String HEADER_CLIENT_PLATFORM = "x-client-platform";

    private static final Logger log = LoggerFactory.getLogger(SessionContextAdapter.class);


    private static final String DEFAULT_CHANNEL = "WEB";
    private static final String DEFAULT_LOCALE = "es-mx";
    private static final String HEADER_PREFIX = "x-";

    /** Opaque session id format: {@code <uuid v4>.<base64 signature>} (ported from digital_bff). */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9+/=]+$");

    private final HttpServletRequest request;
    private final CmsProperties contentProperties;
    private final AuthProperties authProperties;
    private final AuthTokenExchangePort authTokenExchangePort;

    private SessionContext cached;
    private SessionContext cachedWithoutIdentity;

    /**
     * @param request               the in-flight HTTP request (injected by Spring's request-scope proxy)
     * @param contentProperties     content-service config supplying the brand fallback
     * @param authProperties        auth-service config supplying the session cookie name
     * @param authTokenExchangePort exchanges the {@code SessionId} for login/guest + profile id
     */
    public SessionContextAdapter(HttpServletRequest request,
                                 CmsProperties contentProperties,
                                 AuthProperties authProperties,
                                 AuthTokenExchangePort authTokenExchangePort) {
        this.request = request;
        this.contentProperties = contentProperties;
        this.authProperties = authProperties;
        this.authTokenExchangePort = authTokenExchangePort;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds (and memoizes) the session context for this request. The token exchange runs at most
     * once per request even though several controllers call this method. A missing/malformed
     * {@code SessionId} cookie or a failed exchange yields a guest context.</p>
     */
    @Override
    public SessionContext currentContext() {
        if (cached != null) {
            return cached;
        }
        cached = build(true);
        return cached;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds (and memoizes) the context without ever calling {@link AuthTokenExchangePort}. When
     * {@link #currentContext()} already ran for this request its result is returned instead: the
     * exchange is paid for either way, and handing back a guest-shaped copy of a context that is known
     * to be authenticated would be strictly worse than the real thing.</p>
     *
     * <p>The reverse does not hold — a later {@link #currentContext()} still performs the exchange,
     * because this method's memo carries no identity to reuse.</p>
     */
    @Override
    public SessionContext currentContextWithoutIdentity() {
        if (cached != null) {
            return cached;
        }
        if (cachedWithoutIdentity == null) {
            cachedWithoutIdentity = build(false);
        }
        return cachedWithoutIdentity;
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the context from the in-flight request, exchanging the session cookie for the caller's
     * identity only when {@code withIdentity} is set.
     *
     * <p>Everything except the three exchange-derived components is resolved identically either way —
     * including {@link SessionContext#sessionId()}, which comes from a local format check and so costs
     * nothing.</p>
     *
     * @param withIdentity whether to perform the token exchange; {@code false} yields
     *                     {@link AuthExchangeResult#GUEST} without any outbound call
     */
    private SessionContext build(boolean withIdentity) {
        String brand = headerOrDefault(HEADER_BRAND_ID, contentProperties.defaultBrand());
        String channel = headerOrDefault(HEADER_CHANNEL, DEFAULT_CHANNEL);
        String locale = headerOrDefault(HEADER_LOCALE, DEFAULT_LOCALE);
        Map<String, String> headers = snapshotForwardableHeaders();

        String rawSessionId = rawSessionCookieValue();
        String decodedSessionId = decodedIfWellFormed(rawSessionId);
        AuthExchangeResult auth = withIdentity && !declaresGuest(headers)
                ? resolveAuth(decodedSessionId, brand, headers)
                : AuthExchangeResult.GUEST;

        return new SessionContext(
                auth.authenticated(), brand, channel, locale, auth.profileId(), headers,
                // Only a well-formed cookie is carried forward; a malformed one would be rejected
                // downstream anyway and must not travel any further than this class.
                decodedSessionId == null ? null : rawSessionId,
                auth.accessToken(),
                new CohortMarkers(snapshotHeaderMarkers(), snapshotCookieMarkers()));
    }

    /**
     * Whether the caller declared an explicit guest, in which case the exchange is skipped entirely.
     *
     * <p>Reads the snapshot rather than the request so this answers exactly what
     * {@link SessionContext#declaresGuest()} will answer for the context being built. The two must
     * agree: {@code GET /blp|clp/**} decides edge cacheability from the declaration, and Akamai keys the
     * guest slot on the same header — so a request that says {@code false} while carrying a valid
     * {@code SessionId} has to yield a guest body, or a signed-in page would be stored in the guest
     * slot and served to everyone.</p>
     *
     * <p>Skipping the exchange is also what makes an <em>expired</em> cookie cheap: today it costs a
     * round trip to the auth service that ends in a guest downgrade anyway.</p>
     */
    private static boolean declaresGuest(Map<String, String> headers) {
        return SessionContext.CLAIM_GUEST.equalsIgnoreCase(
                headers.get(SessionContext.HEADER_IS_LOGGED_IN));
    }

    /**
     * Exchanges the session cookie for the login/guest signal. The <em>decoded</em> value is what the
     * auth service is given — {@link SessionContext#sessionId()} keeps the raw one for forwarding.
     *
     * <p>Once there is a well-formed cookie, nothing else prevents the call (ADR-070): the contract
     * version comes only from {@code x-auth-version}, and a missing or unrecognised {@code x-channel}
     * exchanges with the default tenant. The platform/channel consistency check only runs for an
     * explicit channel — against the default tenant it would flag every caller that sends none.</p>
     *
     * @param headers the lower-cased {@code x-*} snapshot of this request
     */
    private AuthExchangeResult resolveAuth(String decodedSessionId, String brand, Map<String, String> headers) {
        if (decodedSessionId == null) {
            return AuthExchangeResult.GUEST;
        }
        String rawChannel = headers.get(HEADER_CHANNEL);
        Optional<Channel> channel = Channel.parse(rawChannel);
        AuthTenant tenant = AuthTenant.resolve(channel);
        int version = resolveAuthVersion(headers.get(HEADER_AUTH_VERSION));
        String clientPlatform = trimmedOrNull(headers.get(HEADER_CLIENT_PLATFORM));

        if (channel.isEmpty()) {
            LogEvents.at(log, LogEvents.AUTH_CHANNEL_UNDEFINED)
                    .addKeyValue(LogFields.CHANNEL, rawChannel)
                    .addKeyValue(LogFields.TENANT, tenant.wireValue())
                    .addKeyValue(LogFields.BRAND, brand)
                    .log("No defined x-channel; exchanging with the default tenant");
        } else if (clientPlatform != null && !tenant.expectsPlatform(clientPlatform)) {
            LogEvents.at(log, LogEvents.AUTH_PLATFORM_CHANNEL_MISMATCH)
                    .addKeyValue(LogFields.CHANNEL, rawChannel)
                    .addKeyValue(LogFields.TENANT, tenant.wireValue())
                    .addKeyValue(LogFields.CLIENT_PLATFORM, clientPlatform)
                    .addKeyValue(LogFields.AUTH_VERSION, String.valueOf(version))
                    .log("x-client-platform does not match x-channel; exchanging with the platform as sent");
        }
        return authTokenExchangePort.exchange(
                new AuthExchangeRequest(decodedSessionId, brand, tenant, version, clientPlatform));
    }

    /**
     * The auth contract for this request: the caller's {@code x-auth-version} when it is a supported
     * version, the configured default otherwise. Never an unlisted number — an unpublished version
     * answers {@code 404}, which would degrade the session to guest.
     */
    private int resolveAuthVersion(String rawVersion) {
        String requested = trimmedOrNull(rawVersion);
        if (requested == null) {
            return authProperties.defaultVersion();
        }
        try {
            int version = Integer.parseInt(requested);
            if (authProperties.supportsVersion(version)) {
                return version;
            }
        } catch (NumberFormatException e) {
            // Not a number ("v3"); reported below like any other unsupported value.
        }
        LogEvents.at(log, LogEvents.AUTH_VERSION_UNSUPPORTED)
                .addKeyValue(LogFields.REQUESTED_VERSION, requested)
                .addKeyValue(LogFields.AUTH_VERSION, String.valueOf(authProperties.defaultVersion()))
                .log("Unsupported x-auth-version; exchanging on the default contract");
        return authProperties.defaultVersion();
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * @return the decoded session id when it matches {@link #SESSION_ID_PATTERN}, {@code null} otherwise
     */
    private static String decodedIfWellFormed(String rawSessionId) {
        if (rawSessionId == null) {
            return null;
        }
        String decoded = percentDecode(rawSessionId);
        return SESSION_ID_PATTERN.matcher(decoded).matches() ? decoded : null;
    }

    /**
     * Returns the {@code SessionId} cookie exactly as the browser sent it, still percent-encoded.
     * Callers that need the opaque id itself (format check, token exchange) run it through
     * {@link #percentDecode(String)}; the raw form is what gets forwarded to the shortcuts service.
     */
    private String rawSessionCookieValue() {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (authProperties.sessionCookieName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Percent-decodes a raw cookie value. The servlet API hands cookie values back exactly as the
     * browser stored them, and the web app writes {@code SessionId} URL-encoded, so the base64
     * signature arrives as {@code UCqTmzC%2Fr8zf...} — which fails {@link #SESSION_ID_PATTERN} and
     * silently downgrades every logged-in caller to a guest. digital_bff never saw this because
     * cookie-parser decodes for it.
     *
     * <p>{@code '+'} is pre-escaped so {@link URLDecoder}'s form semantics do not turn a literal
     * plus (a valid base64 character, present when the value arrives undecoded) into a space. A
     * malformed escape sequence is left untouched; the format check downstream rejects it.</p>
     *
     * @param value the raw cookie value
     * @return the decoded value, or {@code value} itself when there is nothing to decode
     */
    private static String percentDecode(String value) {
        if (value == null || value.indexOf('%') < 0) {
            return value;
        }
        try {
            return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Captures every {@code x-*} request header (lower-cased) so blocks can read custom headers. */
    private Map<String, String> snapshotForwardableHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase();
            if (lower.startsWith(HEADER_PREFIX)) {
                headers.put(lower, request.getHeader(name));
            }
        }
        return headers;
    }

    /**
     * Captures every request header, of any name, whose value reads as an A/B cohort marker.
     *
     * <p>Not limited to {@code x-*} like {@link #snapshotForwardableHeaders()}: a
     * {@code conditional_show_hide} rule may name any header. The first value of a repeated header is
     * the one read, as {@link HttpServletRequest#getHeader(String)} does.</p>
     */
    private Map<String, Boolean> snapshotHeaderMarkers() {
        Map<String, Boolean> markers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return markers;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Boolean marker = CohortMarkers.parse(request.getHeader(name));
            if (marker != null) {
                markers.putIfAbsent(name.toLowerCase(Locale.ROOT), marker);
            }
        }
        return markers;
    }

    /**
     * Captures every cookie whose value reads as an A/B cohort marker. Only the first cookie of a
     * given name counts, even when it is unreadable and a later one is not — the same answer the
     * commons evaluator gives by stopping at the first match.
     */
    private Map<String, Boolean> snapshotCookieMarkers() {
        Map<String, Boolean> markers = new HashMap<>();
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return markers;
        }
        Set<String> seen = new HashSet<>();
        for (Cookie cookie : cookies) {
            if (cookie == null || !seen.add(cookie.getName())) {
                continue;
            }
            Boolean marker = CohortMarkers.parse(cookie.getValue());
            if (marker != null) {
                markers.put(cookie.getName(), marker);
            }
        }
        return markers;
    }

    private String headerOrDefault(String headerName, String defaultValue) {
        String value = request.getHeader(headerName);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
