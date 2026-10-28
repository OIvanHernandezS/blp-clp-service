package com.liverpool.ms_clp_blp.shared.cachepurge;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;

/**
 * What a purge covers by default, and the defaults an operator's query parameters override.
 *
 * <p>Nothing here is a secret — the Akamai credential lives in {@code AkamaiProperties}. This record
 * is the answer to "what does purging ms-clp-blp mean", which is a deployment decision rather than a
 * code one: a brand on a different domain changes {@link #siteBaseUrl} and nothing else.</p>
 *
 * <p>{@code enabled} is a genuine kill switch, off, the ops
 * endpoint answers 409 and no Akamai connection is attempted. It is off in the {@code dev} profile so
 * local development can never reach the live edge.</p>
 *
 * @param siteBaseUrl  origin of the public host whose URLs are purged, e.g.
 *                     {@code https://ms-crp-clp-blp-service.liverpool.com.mx}; joined with each entry of
 *                     {@link #paths} because Fast Purge matches absolute URLs, not paths
 * @param paths        endpoint paths to purge by URL; empty by default, because a landing page has
 *                     one URL per category and the cache tag is what covers them all
 * @param tags         {@code Edge-Cache-Tag} values to purge, e.g. {@code ms-clp-blp}; also the only tags
 *                     an operator may name on {@code POST /ops/cache-purge/objects}
 * @param allowedHosts hostnames an operator may purge by absolute URL on
 *                     {@code POST /ops/cache-purge/objects}; when empty, only the host of
 *                     {@link #siteBaseUrl} is allowed
 * @param action       default action when the operator names none
 * @param network      default network when the operator names none
 * @param enabled      kill switch; {@code false} makes every purge endpoint answer 409
 */
@ConfigurationProperties(prefix = "cache-purge")
public record CachePurgeProperties(
        String siteBaseUrl,
        List<String> paths,
        List<String> tags,
        List<String> allowedHosts,
        PurgeAction action,
        PurgeNetwork network,
        boolean enabled) {

    /**
     * Absolute URLs for the configured paths.
     *
     * <p>Blank entries are dropped and a trailing slash on the base URL is trimmed, so a stray
     * {@code CACHE_PURGE_SITE_BASE_URL=https://ms-crp-clp-blp-service.liverpool.com.mx/} cannot produce
     * {@code …liverpool.com.mx//blp/…} — a URL that would be accepted by Akamai and match nothing at
     * all.</p>
     *
     * @return one absolute URL per configured path; empty when none is configured
     */
    public List<String> pathUrls() {
        if (paths == null) {
            return List.of();
        }
        return paths.stream()
                .filter(CachePurgeProperties::isPresent)
                .map(this::absoluteUrl)
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Joins one path onto {@link #siteBaseUrl}, tolerating a trailing slash on the origin and a
     * missing leading slash on the path.
     *
     * @param path a path such as {@code /blp/es-mx/tienda/x/catst1} or {@code blp/es-mx/tienda/x/catst1}
     * @return the absolute URL, or empty when no origin is configured — never a relative URL, which
     *         Akamai would reject
     */
    public Optional<String> absoluteUrl(String path) {
        if (!isPresent(siteBaseUrl) || path == null) {
            return Optional.empty();
        }
        String origin = siteBaseUrl.strip().replaceAll("/+$", "");
        String stripped = path.strip();
        return Optional.of(stripped.startsWith("/") ? origin + stripped : origin + "/" + stripped);
    }

    /**
     * @return the configured cache tags with blanks dropped; empty when none is configured
     */
    public List<String> cacheTags() {
        return present(tags);
    }

    /**
     * Hostnames an operator may purge by absolute URL.
     *
     * <p>Falls back to the host of {@link #siteBaseUrl}, never to "any host": the EdgeGrid credential
     * can usually purge every property of the account, so an open list would let this endpoint
     * invalidate other teams' sites.</p>
     *
     * @return the configured allow-list, or the site's own host; empty when neither is configured
     */
    public List<String> purgeableHosts() {
        List<String> configured = present(allowedHosts);
        if (!configured.isEmpty()) {
            return configured;
        }
        return siteHost().map(List::of).orElse(List.of());
    }

    /**
     * @param host a URL's hostname
     * @return whether {@link #purgeableHosts()} contains it, ignoring case
     */
    public boolean allowsHost(String host) {
        return host != null && purgeableHosts().stream().anyMatch(allowed -> allowed.equalsIgnoreCase(host));
    }

    /**
     * @return the configured default action, or {@link PurgeAction#INVALIDATE} when unset — the
     *         conservative half of the pair, so a missing value can never escalate to an eviction
     */
    public PurgeAction defaultAction() {
        return action == null ? PurgeAction.INVALIDATE : action;
    }

    /**
     * @return the configured default network, or {@link PurgeNetwork#PRODUCTION} when unset, which
     *         is the only network worth defaulting to for a service that exists to purge live content
     */
    public PurgeNetwork defaultNetwork() {
        return network == null ? PurgeNetwork.PRODUCTION : network;
    }

    private Optional<String> siteHost() {
        if (!isPresent(siteBaseUrl)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(URI.create(siteBaseUrl.strip()).getHost())
                    .map(host -> host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static List<String> present(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(CachePurgeProperties::isPresent)
                .map(String::strip)
                .toList();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
