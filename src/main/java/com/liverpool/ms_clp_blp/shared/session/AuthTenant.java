package com.liverpool.ms_clp_blp.shared.session;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The auth-service {@code tenant} a token exchange is made for, derived from an explicitly defined
 * {@link Channel} — never defaulted.
 *
 * <p>{@code WEB}, {@code KIOSCO} and {@code POS} exchange as {@link #WEB}; {@code APP} as {@link #APP}.
 * A request whose channel is missing or unrecognised exchanges as {@link #DEFAULT} ({@code web}) —
 * what every request was sent as before v0.26.0. Skipping the exchange instead (ADR-069) left the
 * Android app, which sends {@code x-client-channel} but no {@code x-channel}, with no session at all
 * (ADR-070). The auth service accepts a request with no {@code tenant} too, but rejects an unknown
 * one with {@code 400 ATG_PROVIDER_NOT_VALID}, so a value is always sent and it is always one of these
 * two.</p>
 *
 * <p>Each tenant also names the {@code x-client-platform} values that belong to it, so a client that
 * sends, say, {@code x-channel: APP} with {@code x-client-platform: web} can be reported. The check is
 * advisory: the auth service decides, ms-clp-blp only makes the inconsistency visible.</p>
 */
public enum AuthTenant {

    WEB("web", Set.of("web")),
    APP("app", Set.of("android", "ios"));

    /** Tenant for a request with no defined channel — the pre-v0.26.0 behaviour (ADR-070). */
    public static final AuthTenant DEFAULT = WEB;

    private final String wireValue;
    private final Set<String> expectedPlatforms;

    AuthTenant(String wireValue, Set<String> expectedPlatforms) {
        this.wireValue = wireValue;
        this.expectedPlatforms = expectedPlatforms;
    }

    /**
     * @param channel an explicitly defined channel
     * @return the tenant for that channel, or empty for {@link Channel#OTHER}
     */
    public static Optional<AuthTenant> forChannel(Channel channel) {
        if (channel == null || channel == Channel.OTHER) {
            return Optional.empty();
        }
        return Optional.of(channel.isApp() ? APP : WEB);
    }

    /**
     * @param channel the strictly parsed channel, or empty when none was defined
     * @return the channel's tenant, or {@link #DEFAULT} when there is none
     */
    public static AuthTenant resolve(Optional<Channel> channel) {
        return channel.flatMap(AuthTenant::forChannel).orElse(DEFAULT);
    }

    /**
     * @return the value sent to the auth service in the {@code tenant} header
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * @param clientPlatform the {@code x-client-platform} the caller sent
     * @return true when the platform is one this tenant expects (case-insensitive)
     */
    public boolean expectsPlatform(String clientPlatform) {
        return clientPlatform != null
                && expectedPlatforms.contains(clientPlatform.trim().toLowerCase(Locale.ROOT));
    }
}
