package com.liverpool.ms_clp_blp.clients.auth;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for the shared auth microservice (session-token exchange).
 *
 * <p>All values are externalised — no URL, path, cookie name, or timeout is ever hardcoded in
 * application logic (Rule 6). ms-clp-blp calls {@code GET {baseUrl}{exchangeTokenPathTemplate}} with the
 * {@code SessionId} cookie value as a query parameter to resolve the login/guest state and profile
 * id of the current request (ported from digital_bff {@code AuthProvider.exchangeToken}).</p>
 *
 * <h3>Why there is a base URL per brand</h3>
 * <p>In production the auth service routes the exchange by the request's <em>Host</em> and ignores the
 * {@code x-brand-id} header: the same {@code SessionId} answers correctly on
 * {@code ogcp-cms-site-d.suburbia.com.mx} and incorrectly on {@code ogcp-cms-site-d.liverpool.com.mx},
 * header notwithstanding. ms-clp-blp is one deployment serving thirteen brands from one origin, so every
 * non-LP brand was asking on Liverpool's host and being degraded to guest — silently, because
 * {@code AuthTokenExchangeAdapter} swallows the failure. {@code brandBaseUrls} is the workaround: a
 * brand listed there has its exchange re-targeted at its own origin by
 * {@link AuthBrandHostInterceptor}. It is a map of exceptions, not a registry — a brand that is absent
 * or blank (the default everywhere) keeps using {@link #baseUrl()}, i.e. today's behaviour.</p>
 *
 * <h3>Why the version is a list, not any number</h3>
 * <p>The caller picks the contract with {@code x-auth-version}, and the path is built from it
 * ({@code /v{version}/auth/exchange-token}). An unpublished version answers {@code 404}, which the
 * adapter turns into a guest — so only the versions listed in {@link #supportedVersions()} are ever
 * sent; anything else falls back to {@link #defaultVersion()}. Releasing a new contract is a change to
 * {@code AUTH_SUPPORTED_VERSIONS}, not to code.</p>
 *
 * @param baseUrl           auth-service base URL
 * @param brandBaseUrls     per-brand origin overrides keyed by brand id ({@code LP}, {@code SB}, …);
 *                          each value must be absolute and carry the same path prefix as
 *                          {@code baseUrl} (e.g. {@code https://host/auth-service})
 * @param exchangeTokenPathTemplate path of the exchange-token endpoint with a {@code {version}}
 *                          placeholder (default {@code /v{version}/auth/exchange-token})
 * @param defaultVersion    contract used when the caller sends no, or no supported,
 *                          {@code x-auth-version} (default {@code 2}); must be in {@code supportedVersions}
 * @param supportedVersions contract versions the auth service has published and ms-clp-blp may call
 *                          (default {@code 2,3})
 * @param sessionCookieName inbound cookie carrying the opaque session id (default {@code SessionId})
 * @param connectTimeout    TCP connect timeout for exchange calls
 * @param readTimeout       HTTP read timeout for exchange calls
 */
@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String baseUrl,
        Map<String, String> brandBaseUrls,
        String exchangeTokenPathTemplate,
        int defaultVersion,
        List<Integer> supportedVersions,
        String sessionCookieName,
        Duration connectTimeout,
        Duration readTimeout) {

    /**
     * Fails startup when the default contract is not one ms-clp-blp is allowed to call: every request
     * without {@code x-auth-version} would otherwise be sent to a path the operator never enabled.
     */
    public AuthProperties {
        supportedVersions = supportedVersions == null ? List.of() : List.copyOf(supportedVersions);
        if (!supportedVersions.contains(defaultVersion)) {
            throw new IllegalStateException("auth.default-version=" + defaultVersion
                    + " must be one of auth.supported-versions=" + supportedVersions);
        }
    }

    /**
     * @param version a contract version requested by the caller
     * @return true when ms-clp-blp may call that version
     */
    public boolean supportsVersion(int version) {
        return supportedVersions.contains(version);
    }
}
