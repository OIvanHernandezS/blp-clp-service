package com.liverpool.ms_clp_blp.shared.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.auth.AuthClient;
import com.liverpool.ms_clp_blp.clients.auth.dto.AuthExchangeResponse;
import com.liverpool.ms_clp_blp.clients.auth.dto.DecodeAccessToken;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import feign.FeignException;

/**
 * Calls the shared auth microservice to exchange a {@code SessionId} for the request's login/guest
 * state and profile id (ported from digital_bff {@code AuthProvider.exchangeToken}).
 *
 * <h3>Wire contract</h3>
 * <pre>GET {baseUrl}/v{version}/auth/exchange-token?SessionId={sessionId}</pre>
 * <p>Headers: {@code x-brand-id} (brand, e.g. {@code LP}/{@code SB}), {@code tenant} (client
 * platform family {@code web}/{@code app}) and, when the caller sent one, {@code x-client-platform}.
 * The version is the caller's {@code x-auth-version} when it is a supported one, else the default
 * ({@code 2}); every contract answers with the same envelope (as in digital_bff, whose {@code AuthProvider.exchangeToken} this
 * mirrors). The login discriminator is {@code decodeAccessToken.isAnonymous}:
 * {@code authenticated = !isAnonymous}; the profile id is {@code prn} when logged in, or
 * {@code anonId} for guests. The response's {@code accessToken} is carried through as well — it is
 * the bearer the decommission stack (wishlist, orders, cart) authenticates with.</p>
 *
 * <p>Any transport/HTTP error is swallowed and mapped to {@link AuthExchangeResult#GUEST} (logged at
 * WARN), mirroring the BFF's non-CSC fallthrough — composition tolerates guests and must not fail the
 * whole request because the auth service is momentarily unavailable.</p>
 */
@Component
public class AuthTokenExchangeAdapter implements AuthTokenExchangePort {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenExchangeAdapter.class);

    /** Groups this adapter's log lines with the Feign transport events for the same system. */
    private static final String UPSTREAM = "auth";

    private final AuthClient authClient;

    /**
     * @param authClient declarative client for the auth service's exchange endpoint
     */
    public AuthTokenExchangeAdapter(AuthClient authClient) {
        this.authClient = authClient;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Performs the exchange call and maps the decoded access token to an {@link AuthExchangeResult}.
     * Returns {@link AuthExchangeResult#GUEST} on any {@link FeignException} or an empty/partial
     * response body.</p>
     *
     * <p>The session id is handed over raw: percent-encoding it is {@link AuthClient}'s
     * responsibility, and getting it wrong degrades every user to guest silently — see that
     * interface's javadoc.</p>
     */
    @Override
    public AuthExchangeResult exchange(AuthExchangeRequest request) {
        try {
            return toResult(call(request));
        } catch (FeignException e) {
            // Covers HTTP error statuses and transport failures alike (RetryableException is a
            // FeignException), preserving the "never fail the page over auth" contract.
            // Worth the detail: this is the failure that silently turns every reader into a guest, so
            // whole personalised blocks vanish from the page with no other trace of why.
            LogEvents.at(log, LogEvents.SESSION_DEGRADED_TO_GUEST)
                    .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                    .addKeyValue(LogFields.BRAND, request.brand())
                    .addKeyValue(LogFields.TENANT, request.tenant().wireValue())
                    .addKeyValue(LogFields.AUTH_VERSION, request.authVersion())
                    .addKeyValue(LogFields.CLIENT_PLATFORM, request.clientPlatform())
                    .addKeyValue(LogFields.UPSTREAM_STATUS, e.status())
                    .addKeyValue(LogFields.ERROR_CLASS, e.getClass().getSimpleName())
                    .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(e))
                    .log("Token exchange failed; the request continues as a guest session");
            return AuthExchangeResult.GUEST;
        }
    }

    private AuthExchangeResponse call(AuthExchangeRequest request) {
        return authClient.exchangeToken(request.version(), request.sessionId(), request.brand(),
                request.tenant().wireValue(), request.clientPlatform());
    }

    private AuthExchangeResult toResult(AuthExchangeResponse response) {
        if (response == null || response.decodeAccessToken() == null) {
            return AuthExchangeResult.GUEST;
        }
        DecodeAccessToken token = response.decodeAccessToken();
        // Authenticated only when isAnonymous is explicitly false — a missing/true flag stays guest.
        boolean authenticated = Boolean.FALSE.equals(token.isAnonymous());
        String profileId = authenticated ? token.prn() : token.anonId();
        return new AuthExchangeResult(authenticated, profileId, response.accessToken());
    }
}
