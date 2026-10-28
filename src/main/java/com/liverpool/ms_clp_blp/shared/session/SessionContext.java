package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;

/**
 * Immutable, request-scoped context ms-clp-blp derives from the current HTTP request.
 *
 * <p>The login/guest signal and {@code profileId} come from an active session-token exchange against
 * the shared auth microservice (see {@link SessionContextAdapter} / {@link AuthTokenExchangePort});
 * brand/channel/locale come from request headers. It also carries a snapshot of every {@code x-*}
 * request header so any block can read the custom header it needs via {@link #header(String)}, and the
 * A/B {@link CohortMarkers} a {@code conditional_show_hide} rule reads by a CMS-authored name.</p>
 *
 * @param authenticated true when the session is logged in; false for guests
 * @param brand         brand identifier (e.g. {@code LP}/{@code SB})
 * @param channel       originating channel (e.g. {@code WEB}/{@code APP}, see {@link Channel})
 * @param locale        content locale (e.g. {@code es-mx})
 * @param profileId     resolved profile id ({@code prn} when logged in, {@code anonId} for guests);
 *                      {@code null} when no session could be resolved
 * @param headers       immutable lower-cased snapshot of the request's {@code x-*} headers
 * @param sessionId     the raw, still percent-encoded {@code SessionId} cookie value, or {@code null}
 *                      when absent/malformed — see {@link #sessionId()} before using it
 * @param accessToken   bearer token issued by the token exchange, or {@code null} when there was no
 *                      exchange — see {@link #authenticatedAccessToken()} before using it
 * @param cohortMarkers the A/B cohort markers the request carried, read by {@code conditional_show_hide}
 *                      rules; {@link CohortMarkers#NONE} when there were none
 */
public record SessionContext(
        boolean authenticated,
        String brand,
        String channel,
        String locale,
        String profileId,
        Map<String, String> headers,
        String sessionId,
        String accessToken,
        CohortMarkers cohortMarkers) {

    public SessionContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        cohortMarkers = cohortMarkers == null ? CohortMarkers.NONE : cohortMarkers;
    }

    /**
     * Convenience constructor for callers that read no A/B cohort markers; {@code cohortMarkers}
     * defaults to {@link CohortMarkers#NONE}. Kept so the existing construction sites (and tests) stay
     * valid.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     * @param profileId     resolved profile id
     * @param headers       snapshot of the request's {@code x-*} headers
     * @param sessionId     the raw {@code SessionId} cookie value
     * @param accessToken   bearer token issued by the token exchange
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale,
                          String profileId, Map<String, String> headers, String sessionId,
                          String accessToken) {
        this(authenticated, brand, channel, locale, profileId, headers, sessionId, accessToken,
                CohortMarkers.NONE);
    }

    /**
     * Convenience constructor for callers that do not need the bearer token; {@code accessToken}
     * defaults to {@code null}. Kept so the existing construction sites (and tests) stay valid.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     * @param profileId     resolved profile id
     * @param headers       snapshot of the request's {@code x-*} headers
     * @param sessionId     the raw {@code SessionId} cookie value
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale,
                          String profileId, Map<String, String> headers, String sessionId) {
        this(authenticated, brand, channel, locale, profileId, headers, sessionId, null);
    }

    /**
     * Convenience constructor for callers that need neither the raw session cookie nor the bearer
     * token; both default to {@code null}. Kept so the many existing construction sites (and tests)
     * stay valid.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     * @param profileId     resolved profile id
     * @param headers       snapshot of the request's {@code x-*} headers
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale,
                          String profileId, Map<String, String> headers) {
        this(authenticated, brand, channel, locale, profileId, headers, null, null);
    }

    /**
     * Convenience constructor for callers (e.g. tests) that only supply the core session signal;
     * profile id defaults to {@code null} and the header snapshot to empty.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale) {
        this(authenticated, brand, channel, locale, null, Map.of(), null, null);
    }

    /**
     * @return true when the session is a guest (not authenticated)
     */
    public boolean isGuest() {
        return !authenticated;
    }

    /**
     * Returns a copy of this context with {@code brand} replaced and <strong>every other component
     * carried over</strong>.
     *
     * <p>Exists so that no caller hand-rolls the copy with a constructor. {@code GET /blp|clp/**} briefly
     * resolved its brand from a {@code ?brand=} query param and rebuilt the record inline, which
     * silently dropped {@link #sessionId()} the moment that component was added — the shortcuts
     * service was never called because the cookie had been nulled out one layer above. That override
     * is gone (brand is {@code x-brand-id} on every endpoint again), so nothing in production calls
     * this today; it is kept as the one supported way to re-brand a context, because the next caller
     * that needs one must not reintroduce that bug. Adding a component to this record means
     * extending this method, never copying it.</p>
     *
     * @param newBrand the brand to use
     * @return a copy differing only in {@code brand}
     */
    public SessionContext withBrand(String newBrand) {
        return new SessionContext(authenticated, newBrand, channel, locale, profileId, headers,
                sessionId, accessToken, cohortMarkers);
    }

    /**
     * Returns a copy of this context with {@code authenticated} replaced and every other component
     * carried over.
     *
     * <p>Exists for {@code GET /blp|clp/**}, which never exchanges the cookie with the auth service
     * (ADR-067): the caller's own {@code x-is-logged-in} declaration is folded into an otherwise
     * guest-shaped context via this method, rather than a caller reconstructing the record by hand
     * (see {@link #withBrand(String)} for why that has already gone wrong once).</p>
     *
     * @param newAuthenticated the login/guest signal to use
     * @return a copy differing only in {@code authenticated}
     */
    public SessionContext withAuthenticated(boolean newAuthenticated) {
        return new SessionContext(newAuthenticated, brand, channel, locale, profileId, headers,
                sessionId, accessToken, cohortMarkers);
    }

    /**
     * Returns {@link #profileId()} only when the session is authenticated; {@code null} for a
     * guest.
     *
     * <p>{@link #profileId()} alone is <strong>not</strong> a safe "is this a real identified
     * user" check: {@link AuthTokenExchangeAdapter} populates it with the anonymous id for guests
     * too (guest exchange result: {@code profileId = token.anonId()}), not with {@code null}. Any
     * caller that gates personalisation on a real identity — e.g. Salesforce's {@code ID_ATG1}
     * (ADR-033) — must go through this accessor rather than {@link #profileId()} directly, or a
     * guest's anonymous id would leak into a request meant to be guest-only.</p>
     *
     * @return the profile id for an authenticated session, {@code null} otherwise
     */
    public String authenticatedProfileId() {
        return authenticated ? profileId : null;
    }

    /**
     * Returns the bearer token from the token exchange, but <strong>only for an authenticated
     * session</strong> — {@code null} for a guest.
     *
     * <p>Mirrors {@link #authenticatedProfileId()} deliberately: the exchange issues a token for an
     * anonymous session too, and sending it to the decommission services (wishlist, orders, cart)
     * would ask them to resolve personal content for a user that does not exist. Every consumer must
     * go through this accessor rather than {@link #accessToken()} directly.</p>
     *
     * <p>A <strong>session secret</strong>: it travels only in an {@code Authorization: Bearer}
     * header to those services, and {@code SensitiveDataMasker} masks that header before any log.</p>
     *
     * @return the bearer token for an authenticated session, {@code null} otherwise
     */
    public String authenticatedAccessToken() {
        return authenticated ? accessToken : null;
    }

    /**
     * Returns the caller's raw {@code SessionId} cookie value — a <strong>session secret</strong>.
     *
     * <p>It is deliberately <em>not</em> percent-decoded: it is the exchange's input and must reach
     * the auth service exactly as the web app wrote it (the base64 signature's {@code +} and
     * {@code /} arrive escaped). Decoding here and re-encoding downstream is how a valid session
     * silently turns into a 401.</p>
     *
     * <p>Not an identity signal and never a substitute for {@link #authenticatedProfileId()}: it is
     * present for a guest with a valid cookie too. Since the shortcuts rail moved off the web-bff it
     * has no downstream consumer left — the identity that reaches the decommission services is
     * {@link #authenticatedAccessToken()}. Nothing may log it (the outbound interceptor masks
     * {@code Cookie}) or forward it to a host other than the auth service.</p>
     *
     * @return the raw cookie value, or {@code null} when absent or malformed
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Looks up a captured request header value by name (case-insensitive).
     *
     * <p>Not for identity: this returns whatever the caller sent, unvalidated. Use {@link
     * #authenticatedProfileId()} for anything that gates personalisation on a real user (ADR-033).</p>
     *
     * @param name the header name (e.g. {@code x-jml-user-id})
     * @return the header value, or {@code null} when absent
     */
    public String header(String name) {
        return name == null ? null : headers.get(name.toLowerCase());
    }

    /**
     * The header by which the caller <em>declares</em> whether a user is signed in.
     *
     * <p>A declaration, not a fact: it is whatever the caller sent. Its only two uses are the guest
     * short circuit in {@link SessionContextAdapter} and the edge-cacheability decision on
     * {@code GET /blp|clp/**} — and those two must read the same signal, or the edge would key a response
     * under a variant the origin did not actually produce.</p>
     */
    public static final String HEADER_IS_LOGGED_IN = "x-is-logged-in";

    /** The {@link #HEADER_IS_LOGGED_IN} value that declares a guest. */
    static final String CLAIM_GUEST = "false";

    /** The {@link #HEADER_IS_LOGGED_IN} value that declares a signed-in user. */
    static final String CLAIM_LOGGED_IN = "true";

    /**
     * Whether the caller declared an explicit guest ({@code x-is-logged-in: false}).
     *
     * <p>An absent header is <strong>not</strong> a guest declaration: today's callers may omit it
     * while still carrying a valid {@code SessionId}, and treating that as a guest would sign them
     * out. Only the explicit value is honoured.</p>
     *
     * @return true when the caller explicitly declared a guest session
     */
    public boolean declaresGuest() {
        return CLAIM_GUEST.equalsIgnoreCase(header(HEADER_IS_LOGGED_IN));
    }

    /**
     * Whether the caller declared a signed-in user ({@code x-is-logged-in: true}).
     *
     * @return true when the caller explicitly declared a signed-in session
     */
    public boolean declaresLoggedIn() {
        return CLAIM_LOGGED_IN.equalsIgnoreCase(header(HEADER_IS_LOGGED_IN));
    }
}
