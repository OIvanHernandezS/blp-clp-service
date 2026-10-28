package com.liverpool.ms_clp_blp.shared.session;

/**
 * Outbound port exposing the current request's session context (login/guest + brand/channel/locale),
 * derived from the upstream-validated token. Keeps request-scoped, framework-specific access out of
 * the domain.
 *
 * <h3>Two methods, because identity costs a network call</h3>
 * <p>{@link #currentContext()} resolves login/guest by exchanging the {@code SessionId} cookie with
 * the auth service — a round trip on every request that carries a valid cookie.
 * {@link #currentContextWithoutIdentity()} skips it. Callers that read nothing the exchange produces
 * must use the latter; see its javadoc for the (enforced) list of accessors that then become
 * off-limits.</p>
 */
public interface SessionContextPort {

    /**
     * @return the session context for the in-flight request, with login/guest and profile id resolved
     *         by a token exchange against the auth service
     */
    SessionContext currentContext();

    /**
     * Returns the request's context <strong>without performing the token exchange</strong> — no
     * outbound call is made, so the auth service's latency and availability leave this path alone.
     *
     * <p>For endpoints that only ever read request-derived values: {@link SessionContext#brand()},
     * {@link SessionContext#channel()}, {@link SessionContext#locale()},
     * {@link SessionContext#header(String)} and {@link SessionContext#sessionId()}. That last one is
     * populated here too — it comes from a local format check on the cookie, not from the exchange —
     * so the ADR-041 "a well-formed session travelled" guard keeps working.</p>
     *
     * <p>The three exchange-derived components come back guest-shaped
     * ({@code authenticated=false}, {@code profileId=null}, {@code accessToken=null}) because a record
     * cannot leave them absent. Reading them here is therefore a <em>bug</em>, not a signal: it would
     * report a logged-in caller as a guest. Guest-shaped rather than exception-throwing is deliberate —
     * it is exactly what {@link AuthExchangeResult#GUEST} yields when the auth service is unreachable, a
     * state every consumer already tolerates, so the failure mode is "less content", never a leak.</p>
     *
     * <p>{@code ArchitectureTest.identityFreeSlicesDoNotReadIdentity} fails the build if a slice wired
     * to this method reads any of the three. Extend that rule, not this contract, when a new slice
     * opts in.</p>
     *
     * @return the session context for the in-flight request, minus the exchanged identity
     */
    SessionContext currentContextWithoutIdentity();
}
