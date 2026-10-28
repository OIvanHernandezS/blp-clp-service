package com.liverpool.ms_clp_blp.shared.cachepurge;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeNotConfiguredException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeRequest;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeTargetKind;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgePort;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgeService;
import com.liverpool.ms_clp_blp.shared.errors.ValidationException;

/**
 * Turns "purge ms-clp-blp" into the concrete calls a CDN understands.
 *
 * <p>The only interesting decision here is that a full purge is two calls, not one: Akamai separates
 * URL and cache-tag purges into different endpoints, so the configured paths and the
 * {@code ms-clp-blp} tag can never travel together.</p>
 *
 * <p>Failures are <strong>not</strong> contained. This is deliberate: a purge reported as
 * successful while half the edge still serves stale content is the failure most likely to be believed.
 * The first rejection propagates and the operator sees it.</p>
 */
@Service
public class CachePurgeUseCases implements CachePurgeService {

    /**
     * Cap on URLs plus tags in one operator request. Well inside Fast Purge's 50,000-byte body limit,
     * and far more than an operator types by hand — a larger list is a script that should batch.
     */
    static final int MAX_OBJECTS = 200;

    /** An entry with a URI scheme ({@code https://…}); anything else is a site-relative path. */
    private static final Pattern ABSOLUTE_URL = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private final CachePurgePort cachePurgePort;
    private final CachePurgeProperties properties;

    /**
     * @param cachePurgePort the CDN this service purges through
     * @param properties     what a purge covers, and the operator-overridable defaults
     */
    public CachePurgeUseCases(CachePurgePort cachePurgePort, CachePurgeProperties properties) {
        this.cachePurgePort = cachePurgePort;
        this.properties = properties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeReport purgeAll(PurgeAction action, PurgeNetwork network) {
        return submit(properties.pathUrls(), properties.cacheTags(), action, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeReport purgePaths(PurgeAction action, PurgeNetwork network) {
        return submit(properties.pathUrls(), List.of(), action, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeReport purgeTags(PurgeAction action, PurgeNetwork network) {
        return submit(List.of(), properties.cacheTags(), action, network);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Every entry is validated before the first call, so a bad entry costs zero requests rather
     * than leaving the URL group purged and the tag group not. Duplicates are dropped.</p>
     */
    @Override
    public PurgeReport purgeObjects(PurgeAction action, PurgeNetwork network, List<String> urls,
                                    List<String> tags) {
        List<String> resolvedUrls = resolve(urls, this::resolveUrl);
        List<String> resolvedTags = resolve(tags, this::resolveTag);
        int total = resolvedUrls.size() + resolvedTags.size();
        if (total == 0) {
            throw new ValidationException("Nothing to purge.",
                    "Provide at least one entry in 'urls' or 'tags'.");
        }
        if (total > MAX_OBJECTS) {
            throw new ValidationException("Too many objects to purge.",
                    "At most " + MAX_OBJECTS + " URLs and tags per request; got " + total + ".");
        }
        return submit(resolvedUrls, resolvedTags, action, network);
    }

    private static List<String> resolve(List<String> entries, UnaryOperator<String> resolver) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream().map(resolver).distinct().toList();
    }

    /**
     * Turns one operator entry into the absolute URL Akamai matches.
     *
     * @param entry an absolute URL or a site-relative path
     * @return the absolute URL to purge
     */
    private String resolveUrl(String entry) {
        String value = requirePresent(entry, "urls");
        String absolute = ABSOLUTE_URL.matcher(value).find() ? value : properties.absoluteUrl(value)
                .orElseThrow(() -> new ValidationException("Relative purge path without a site.",
                        "'" + value + "' is relative and cache-purge.site-base-url is not configured; "
                                + "send an absolute URL."));
        URI uri = parseHttpUri(absolute);
        if (!properties.allowsHost(uri.getHost())) {
            throw new ValidationException("Host not allowed for purge.",
                    "'" + uri.getHost() + "' is not in cache-purge.allowed-hosts "
                            + properties.purgeableHosts() + ".");
        }
        return absolute;
    }

    private static URI parseHttpUri(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return uri;
            }
        } catch (IllegalArgumentException e) {
            // Reported below with the same message as a non-http URL.
        }
        throw new ValidationException("Malformed purge URL.",
                "'" + url + "' is not an absolute http(s) URL with a host.");
    }

    /**
     * Accepts only the configured tags: a cache tag spans every property of the Akamai account that
     * emits it, so an arbitrary tag could purge content this service does not own.
     *
     * @param entry a cache tag
     * @return the stripped tag
     */
    private String resolveTag(String entry) {
        String tag = requirePresent(entry, "tags");
        if (!properties.cacheTags().contains(tag)) {
            throw new ValidationException("Cache tag not allowed for purge.",
                    "'" + tag + "' is not in cache-purge.tags " + properties.cacheTags() + ".");
        }
        return tag;
    }

    private static String requirePresent(String entry, String field) {
        if (entry == null || entry.isBlank()) {
            throw new ValidationException("Blank purge entry.",
                    "'" + field + "' must not contain blank entries.");
        }
        return entry.strip();
    }

    /**
     * Submits the non-empty groups, URLs first.
     *
     * <p>The credential is checked before the first call rather than per group, so an unconfigured
     * environment costs zero requests. An empty group is skipped rather than sent: Akamai answers a
     * request with no objects with a 400, which would read as a broken integration rather than as
     * "nothing is configured to purge".</p>
     *
     * @param urls    absolute URLs to purge, possibly empty
     * @param tags    cache tags to purge, possibly empty
     * @param action  invalidate or delete
     * @param network staging or production
     * @return one outcome per submitted group
     */
    private PurgeReport submit(List<String> urls, List<String> tags, PurgeAction action,
                               PurgeNetwork network) {
        if (!cachePurgePort.configured()) {
            throw new CachePurgeNotConfiguredException();
        }
        List<PurgeOutcome> outcomes = new ArrayList<>();
        if (!urls.isEmpty()) {
            outcomes.add(cachePurgePort.purge(
                    new PurgeRequest(PurgeTargetKind.URL, action, network, urls)));
        }
        if (!tags.isEmpty()) {
            outcomes.add(cachePurgePort.purge(
                    new PurgeRequest(PurgeTargetKind.TAG, action, network, tags)));
        }
        return new PurgeReport(outcomes);
    }
}
