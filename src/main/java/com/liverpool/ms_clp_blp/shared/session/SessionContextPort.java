package com.liverpool.ms_clp_blp.shared.session;

/**
 * Outbound port exposing the current request's session context (brand/channel/locale, the {@code x-*}
 * header snapshot, the format-checked {@code SessionId} cookie and the A/B cohort markers). Keeps
 * request-scoped, framework-specific access out of the domain.
 *
 * <p>Building it makes no outbound call. The context comes back guest-shaped
 * ({@code authenticated=false}): a caller that needs the login/guest signal folds in the caller's own
 * {@code x-is-logged-in} declaration, as {@code GET /blp|clp/**} does (ADR-067).
 * {@code ArchitectureTest.identityFreeSlicesDoNotReadIdentity} fails the build if a slice that does
 * not fold it in reads {@code authenticated}/{@code isGuest}.</p>
 */
public interface SessionContextPort {

    /**
     * @return the session context for the in-flight request
     */
    SessionContext currentContext();
}
