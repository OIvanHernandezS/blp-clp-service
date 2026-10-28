package com.liverpool.ms_clp_blp.shared.cachepurge.ports;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeRequest;

/**
 * Outbound port for whatever CDN actually performs the purge.
 *
 * <p>The single seam that keeps Akamai's specifics — EdgeGrid signing, the {@code /ccu/v3} paths, its
 * response envelope — out of the rest of the application. Everything above this interface speaks only
 * in {@link PurgeRequest} and {@link PurgeOutcome}.</p>
 */
public interface CachePurgePort {

    /**
     * Whether this CDN has everything it needs to authenticate a purge.
     *
     * <p>Asked before submitting so an unconfigured environment fails with a configuration error
     * instead of an authentication one. It lives on the port rather than being read from the CDN's
     * properties directly, which is what keeps the use case from knowing that "configured" happens to
     * mean four EdgeGrid values.</p>
     *
     * @return {@code true} when a purge could be authenticated
     */
    boolean configured();

    /**
     * Submits one purge and waits for the CDN to accept it.
     *
     * <p>Acceptance is not completion: CDN purges are asynchronous, so a returned outcome means the
     * request was taken, not that the objects are already gone.</p>
     *
     * @param request the objects to purge, with the action and network to apply
     * @return what the CDN answered, including its correlation ids
     * @throws com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeFailedException when the CDN
     *         rejected the purge, could not be reached, timed out, or answered something unusable
     */
    PurgeOutcome purge(PurgeRequest request);
}
