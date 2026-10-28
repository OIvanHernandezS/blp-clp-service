package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.List;

/**
 * One purge to submit: a set of objects of a single kind, with the action and network to apply.
 *
 * @param kind    whether {@link #objects} are URLs or cache tags
 * @param action  invalidate or delete
 * @param network staging or production
 * @param objects the URLs or tags; never empty — the use case drops an empty group rather than
 *                sending a request Akamai would answer with a 400
 */
public record PurgeRequest(
        PurgeTargetKind kind,
        PurgeAction action,
        PurgeNetwork network,
        List<String> objects) {

    /**
     * @param kind    target kind
     * @param action  purge action
     * @param network target network
     * @param objects the URLs or tags, defensively copied so the request cannot change after the
     *                log line describing it has been written
     */
    public PurgeRequest {
        objects = List.copyOf(objects);
    }
}
