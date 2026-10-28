package com.liverpool.ms_clp_blp.shared.session;

/**
 * Outbound port that exchanges an opaque {@code SessionId} for the request's login/guest state and
 * profile id by calling the shared auth microservice. Keeps request-scoped, framework-specific HTTP
 * access out of {@link SessionContextAdapter}.
 */
public interface AuthTokenExchangePort {

    /**
     * Exchanges the given session id for the decoded login/guest state.
     *
     * @param request session id, brand, tenant, contract version and client platform of the exchange
     * @return the exchange result; {@link AuthExchangeResult#GUEST} when the call fails
     */
    AuthExchangeResult exchange(AuthExchangeRequest request);
}
