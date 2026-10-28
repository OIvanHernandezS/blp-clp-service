package com.liverpool.ms_clp_blp.shared.cachepurge.ports;

import java.util.List;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;

/**
 * Inbound port for triggering an edge-cache purge.
 *
 * <p>Every method takes the action and network explicitly rather than reading them from
 * configuration: the operator chooses both per call, and the defaults are resolved at the HTTP
 * boundary so that this port has no hidden dependency on which environment it runs in.</p>
 */
public interface CachePurgeService {

    /**
     * Purges everything this service owns at the edge: the configured endpoint paths
     * ({@code cache-purge.paths}) <em>and</em> the configured cache tags
     * ({@code ms-clp-blp}).
     *
     * @param action  invalidate or delete
     * @param network staging or production
     * @return one entry per submitted purge
     */
    PurgeReport purgeAll(PurgeAction action, PurgeNetwork network);

    /**
     * Purges the configured endpoint paths only, by URL.
     *
     * @param action  invalidate or delete
     * @param network staging or production
     * @return one entry, for the URL purge
     */
    PurgeReport purgePaths(PurgeAction action, PurgeNetwork network);

    /**
     * Purges the configured {@code Edge-Cache-Tag} values only.
     *
     * @param action  invalidate or delete
     * @param network staging or production
     * @return one entry, for the tag purge
     */
    PurgeReport purgeTags(PurgeAction action, PurgeNetwork network);

    /**
     * Purges exactly the URLs and cache tags the operator names, instead of the configured set.
     *
     * <p>Relative paths are joined onto the configured site; absolute URLs must be on an allowed host
     * and tags must be among the configured ones, because the credential can reach far more of the
     * account than this service owns.</p>
     *
     * @param action  invalidate or delete
     * @param network staging or production
     * @param urls    absolute URLs or site-relative paths; may be {@code null} or empty
     * @param tags    cache tags; may be {@code null} or empty
     * @return one entry per submitted group, URLs first
     * @throws com.liverpool.ms_clp_blp.shared.errors.ValidationException when nothing is named, an entry is
     *         blank or malformed, a host or tag is not allowed, or too many objects are named
     */
    PurgeReport purgeObjects(PurgeAction action, PurgeNetwork network, List<String> urls, List<String> tags);
}
