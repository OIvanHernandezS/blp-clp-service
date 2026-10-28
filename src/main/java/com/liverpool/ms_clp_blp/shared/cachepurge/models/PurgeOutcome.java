package com.liverpool.ms_clp_blp.shared.cachepurge.models;

import java.util.List;

/**
 * What Akamai answered for one submitted {@link PurgeRequest}.
 *
 * <p>{@link #purgeId} and {@link #supportId} are Akamai's own correlation ids and are the only way to
 * ask Akamai afterwards what became of a purge, so both are logged and returned to the operator.
 * Either may be {@code null} if the upstream omitted it.</p>
 *
 * @param kind             what was purged
 * @param action           the action applied
 * @param network          the network purged
 * @param objects          the URLs or tags submitted, echoed so the response is self-describing
 * @param purgeId          Akamai's id for this purge
 * @param supportId        id to quote to Akamai Technical Support
 * @param estimatedSeconds Akamai's estimate of propagation time; the purge is asynchronous, so this
 *                         is the only completion signal available
 */
public record PurgeOutcome(
        PurgeTargetKind kind,
        PurgeAction action,
        PurgeNetwork network,
        List<String> objects,
        String purgeId,
        String supportId,
        Integer estimatedSeconds) {

    /**
     * @param kind             target kind
     * @param action           purge action
     * @param network          target network
     * @param objects          submitted objects, defensively copied
     * @param purgeId          Akamai purge id
     * @param supportId        Akamai support id
     * @param estimatedSeconds Akamai propagation estimate
     */
    public PurgeOutcome {
        objects = List.copyOf(objects);
    }

    /**
     * @return how many objects this purge covered — the number worth alerting on when it drops to
     *         zero unexpectedly
     */
    public int objectCount() {
        return objects.size();
    }
}
