package com.liverpool.ms_clp_blp.shared.session;

import java.util.Map;

/**
 * Immutable, request-scoped context ms-clp-blp derives from the current HTTP request.
 *
 * <p>Everything here comes from the request itself — no outbound call is made to build it (see
 * {@link SessionContextAdapter}). Brand/channel/locale come from request headers; the login/guest
 * signal is the caller's own {@code x-is-logged-in} declaration, folded in by the page endpoint
 * (ADR-067). It also carries a snapshot of every {@code x-*} request header so any block can read the
 * custom header it needs via {@link #header(String)}, and the A/B {@link CohortMarkers} a
 * {@code conditional_show_hide} rule reads by a CMS-authored name.</p>
 *
 * @param authenticated true when the caller declared a signed-in user; false for guests
 * @param brand         brand identifier (e.g. {@code LP}/{@code SB})
 * @param channel       originating channel (e.g. {@code WEB}/{@code APP}, see {@link Channel})
 * @param locale        content locale (e.g. {@code es-mx})
 * @param headers       immutable lower-cased snapshot of the request's {@code x-*} headers
 * @param sessionId     the raw, still percent-encoded {@code SessionId} cookie value, or {@code null}
 *                      when absent/malformed — see {@link #sessionId()} before using it
 * @param cohortMarkers the A/B cohort markers the request carried, read by {@code conditional_show_hide}
 *                      rules; {@link CohortMarkers#NONE} when there were none
 */
public record SessionContext(
        boolean authenticated,
        String brand,
        String channel,
        String locale,
        Map<String, String> headers,
        String sessionId,
        CohortMarkers cohortMarkers) {

    public SessionContext {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        cohortMarkers = cohortMarkers == null ? CohortMarkers.NONE : cohortMarkers;
    }

    /**
     * Convenience constructor for callers that read no A/B cohort markers; {@code cohortMarkers}
     * defaults to {@link CohortMarkers#NONE}.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     * @param headers       snapshot of the request's {@code x-*} headers
     * @param sessionId     the raw {@code SessionId} cookie value
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale,
                          Map<String, String> headers, String sessionId) {
        this(authenticated, brand, channel, locale, headers, sessionId, CohortMarkers.NONE);
    }

    /**
     * Convenience constructor for callers (e.g. tests) that only supply the core session signal;
     * the header snapshot defaults to empty and there is no session cookie.
     *
     * @param authenticated login/guest signal
     * @param brand         brand identifier
     * @param channel       originating channel
     * @param locale        content locale
     */
    public SessionContext(boolean authenticated, String brand, String channel, String locale) {
        this(authenticated, brand, channel, locale, Map.of(), null);
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
     * silently dropped {@link #sessionId()} the moment that component was added. That override
     * is gone (brand is {@code x-brand-id} on every endpoint again), so nothing in production calls
     * this today; it is kept as the one supported way to re-brand a context, because the next caller
     * that needs one must not reintroduce that bug. Adding a component to this record means
     * extending this method, never copying it.</p>
     *
     * @param newBrand the brand to use
     * @return a copy differing only in {@code brand}
     */
    public SessionContext withBrand(String newBrand) {
        return new SessionContext(authenticated, newBrand, channel, locale, headers, sessionId,
                cohortMarkers);
    }

    /**
     * Returns a copy of this context with {@code authenticated} replaced and every other component
     * carried over.
     *
     * <p>Exists for {@code GET /blp|clp/**} (ADR-067): the caller's own {@code x-is-logged-in}
     * declaration is folded into an otherwise guest-shaped context via this method, rather than a
     * caller reconstructing the record by hand (see {@link #withBrand(String)} for why that has
     * already gone wrong once).</p>
     *
     * @param newAuthenticated the login/guest signal to use
     * @return a copy differing only in {@code authenticated}
     */
    public SessionContext withAuthenticated(boolean newAuthenticated) {
        return new SessionContext(newAuthenticated, brand, channel, locale, headers, sessionId,
                cohortMarkers);
    }

    /**
     * Returns the caller's raw {@code SessionId} cookie value — a <strong>session secret</strong>.
     *
     * <p>Only a well-formed cookie is carried (format-checked locally, never exchanged). It is not an
     * identity signal — it is present for a guest with a valid cookie too. Its two readers only ask
     * whether one travelled: the edge-cacheability guard on {@code GET /blp|clp/**} (ADR-041) and the
     * Salesforce V1 gate in {@code SalesforceSettings#resolveUserId}. Nothing may log it (the outbound
     * interceptor masks {@code Cookie}) or forward it to another host.</p>
     *
     * @return the raw cookie value, or {@code null} when absent or malformed
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Looks up a captured request header value by name (case-insensitive).
     *
     * <p>Not for identity: this returns whatever the caller sent, unvalidated.</p>
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
     * <p>A declaration, not a fact: it is whatever the caller sent. {@code GET /blp|clp/**} folds it
     * into {@link #authenticated()} and decides edge cacheability from it — and both must read the
     * same signal, or the edge would key a response under a variant the origin did not produce.</p>
     */
    public static final String HEADER_IS_LOGGED_IN = "x-is-logged-in";

    /** The {@link #HEADER_IS_LOGGED_IN} value that declares a guest. */
    static final String CLAIM_GUEST = "false";

    /** The {@link #HEADER_IS_LOGGED_IN} value that declares a signed-in user. */
    static final String CLAIM_LOGGED_IN = "true";

    /**
     * Whether the caller declared an explicit guest ({@code x-is-logged-in: false}).
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
