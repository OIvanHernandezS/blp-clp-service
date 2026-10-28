package com.liverpool.ms_clp_blp.shared.session;

/**
 * Outcome of a session-token exchange: the login/guest signal, the resolved profile id and the
 * bearer token the exchange issued.
 *
 * <p>Framework-free result the {@link SessionContextAdapter} folds into the request-scoped
 * {@link SessionContext}. {@code authenticated} is derived from the decoded access token
 * ({@code !isAnonymous}); {@code profileId} is the {@code prn} for logged-in users or the
 * {@code anonId} for guests (may be {@code null} when the exchange could not be performed).</p>
 *
 * @param authenticated true when the session is logged in; false for guests / failed exchange
 * @param profileId     resolved profile id ({@code prn} or {@code anonId}); {@code null} when unknown
 * @param accessToken   bearer token for the decommission stack; a <strong>session secret</strong>,
 *                      {@code null} when the exchange could not be performed
 */
public record AuthExchangeResult(boolean authenticated, String profileId, String accessToken) {

    /** Shared guest outcome used when no cookie is present or the exchange fails. */
    public static final AuthExchangeResult GUEST = new AuthExchangeResult(false, null, null);
}
