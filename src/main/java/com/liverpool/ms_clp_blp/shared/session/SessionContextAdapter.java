package com.liverpool.ms_clp_blp.shared.session;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import com.liverpool.ms_clp_blp.clients.contentstack.CmsProperties;

/**
 * Request-scoped adapter that builds the {@link SessionContext} for the in-flight HTTP request.
 *
 * <p>Everything comes from the request itself — no outbound call. Brand/channel/locale come from
 * upstream headers, every {@code x-*} header is snapshotted so blocks can read their own custom
 * headers via {@link SessionContext#header(String)}, and the {@code SessionId} cookie is carried
 * forward only when its opaque format is valid. The context is guest-shaped; the login/guest signal is
 * the caller's {@code x-is-logged-in} declaration, which the page endpoint folds in (ADR-067).</p>
 *
 * <h3>Expected request inputs:</h3>
 * <ul>
 *   <li>{@code SessionId} cookie — opaque session id; only its presence and format are checked</li>
 *   <li>{@code x-brand-id} — brand identifier; falls back to {@code cms.default-brand}</li>
 *   <li>{@code x-channel} — originating channel ({@code WEB}/{@code APP}/{@code KIOSCO}/{@code POS});
 *       defaults to {@code WEB}</li>
 *   <li>{@code x-locale} — content locale (e.g. {@code es-mx})</li>
 * </ul>
 */
@Component
@RequestScope
public class SessionContextAdapter implements SessionContextPort {

    static final String HEADER_BRAND_ID = "x-brand-id";
    static final String HEADER_CHANNEL = "x-channel";
    static final String HEADER_LOCALE = "x-locale";
    static final String SESSION_COOKIE_NAME = "SessionId";

    private static final String DEFAULT_CHANNEL = "WEB";
    private static final String DEFAULT_LOCALE = "es-mx";
    private static final String HEADER_PREFIX = "x-";

    /** Opaque session id format: {@code <uuid v4>.<base64 signature>} (ported from digital_bff). */
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[A-Za-z0-9+/=]+$");

    private final HttpServletRequest request;
    private final CmsProperties contentProperties;

    private SessionContext cached;

    /**
     * @param request           the in-flight HTTP request (injected by Spring's request-scope proxy)
     * @param contentProperties content-service config supplying the brand fallback
     */
    public SessionContextAdapter(HttpServletRequest request, CmsProperties contentProperties) {
        this.request = request;
        this.contentProperties = contentProperties;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Builds (and memoizes) the session context for this request.</p>
     */
    @Override
    public SessionContext currentContext() {
        if (cached == null) {
            cached = build();
        }
        return cached;
    }

    // ── private ──────────────────────────────────────────────────────────────────────────────────

    private SessionContext build() {
        String brand = headerOrDefault(HEADER_BRAND_ID, contentProperties.defaultBrand());
        String channel = headerOrDefault(HEADER_CHANNEL, DEFAULT_CHANNEL);
        String locale = headerOrDefault(HEADER_LOCALE, DEFAULT_LOCALE);
        String rawSessionId = rawSessionCookieValue();

        return new SessionContext(
                false, brand, channel, locale, snapshotForwardableHeaders(),
                // Only a well-formed cookie is carried forward; a malformed one must not travel any
                // further than this class.
                isWellFormed(rawSessionId) ? rawSessionId : null,
                new CohortMarkers(snapshotHeaderMarkers(), snapshotCookieMarkers()));
    }

    /**
     * @return whether the percent-decoded value matches {@link #SESSION_ID_PATTERN}
     */
    private static boolean isWellFormed(String rawSessionId) {
        return rawSessionId != null && SESSION_ID_PATTERN.matcher(percentDecode(rawSessionId)).matches();
    }

    /**
     * Returns the {@code SessionId} cookie exactly as the browser sent it, still percent-encoded.
     * The format check runs it through {@link #percentDecode(String)} first.
     */
    private String rawSessionCookieValue() {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * Percent-decodes a raw cookie value. The servlet API hands cookie values back exactly as the
     * browser stored them, and the web app writes {@code SessionId} URL-encoded, so the base64
     * signature arrives as {@code UCqTmzC%2Fr8zf...} — which would fail {@link #SESSION_ID_PATTERN}
     * undecoded. digital_bff never saw this because cookie-parser decodes for it.
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
