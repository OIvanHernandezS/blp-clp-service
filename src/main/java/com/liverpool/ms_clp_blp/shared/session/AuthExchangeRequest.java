package com.liverpool.ms_clp_blp.shared.session;

/**
 * Everything one session-token exchange is made with.
 *
 * <p>A record rather than positional arguments on {@link AuthTokenExchangePort#exchange}: the versioned
 * auth contract added two inputs, and four adjacent {@code String}s are one transposition away from
 * sending the brand as the platform.</p>
 *
 * @param sessionId      the decoded {@code SessionId} (already format-validated)
 * @param brand          brand identifier sent as {@code x-brand-id} (e.g. {@code LP}/{@code SB})
 * @param tenant         client platform family sent as {@code tenant}
 * @param version        auth contract version to call ({@code 2}, {@code 3}, …), already checked
 *                       against the supported versions
 * @param clientPlatform the caller's {@code x-client-platform}, forwarded to the auth service only;
 *                       {@code null} when not sent
 */
public record AuthExchangeRequest(
        String sessionId,
        String brand,
        AuthTenant tenant,
        int version,
        String clientPlatform) {

    /**
     * @return the contract version as text, for logging which contract the exchange used
     */
    public String authVersion() {
        return String.valueOf(version);
    }

    /**
     * The session id is a session secret: kept out of {@code toString()} so the record can never leak
     * it into a log line or an assertion message.
     */
    @Override
    public String toString() {
        return "AuthExchangeRequest[brand=" + brand + ", tenant=" + tenant + ", version=" + version
                + ", clientPlatform=" + clientPlatform + "]";
    }
}
