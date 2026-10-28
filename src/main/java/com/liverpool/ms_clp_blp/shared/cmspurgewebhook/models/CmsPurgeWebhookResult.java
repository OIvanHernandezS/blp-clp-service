package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeAction;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeNetwork;

/**
 * What one Contentstack delivery caused.
 *
 * <p>Read by whoever is looking at the delivery log in Contentstack, which is why {@code reason} is
 * prose rather than a code: the status is always 200, so this is the only place the outcome shows.</p>
 *
 * <p>{@link #action} and {@link #network} are {@code null} when no purge was attempted. That is
 * deliberate — reporting {@code "invalidate"} on a delivery that purged nothing would read as a purge
 * that covered zero objects.</p>
 *
 * @param purged      whether a purge was submitted and accepted
 * @param contentType the content type the delivery was about, or {@code null} when it carried none
 * @param event       the Contentstack event, e.g. {@code publish}
 * @param action      the action submitted, or {@code null} when nothing was
 * @param network     the network purged, or {@code null} when nothing was
 * @param objectCount how many URLs the purge covered; {@code 0} when nothing was submitted
 * @param reason      why the delivery had the effect it did
 */
public record CmsPurgeWebhookResult(
        boolean purged,
        String contentType,
        String event,
        PurgeAction action,
        PurgeNetwork network,
        int objectCount,
        String reason) {

    /**
     * The delivery was acknowledged and deliberately did nothing.
     *
     * @param contentType content type the delivery was about, possibly {@code null}
     * @param event       the Contentstack event, possibly {@code null}
     * @param reason      why it was not actionable
     * @return an outcome reporting that nothing was purged
     */
    public static CmsPurgeWebhookResult ignored(String contentType, String event, String reason) {
        return new CmsPurgeWebhookResult(false, contentType, event, null, null, 0, reason);
    }

    /**
     * The purge was submitted and the CDN accepted it.
     *
     * @param contentType content type that triggered it
     * @param event       the Contentstack event
     * @param action      the action submitted
     * @param network     the network purged
     * @param objectCount how many URLs it covered
     * @return an outcome reporting the accepted purge
     */
    public static CmsPurgeWebhookResult purged(String contentType, String event, PurgeAction action,
                                               PurgeNetwork network, int objectCount) {
        return new CmsPurgeWebhookResult(true, contentType, event, action, network, objectCount,
                "Submitted " + action.wireValue() + " for " + objectCount + " URL(s) on the "
                        + network.wireValue() + " network");
    }

    /**
     * The delivery was relevant, the purge was attempted, and it did not go through.
     *
     * @param contentType content type that triggered it
     * @param event       the Contentstack event
     * @param action      the action that was attempted
     * @param network     the network that was targeted
     * @param reason      what went wrong, in the failure's own words
     * @return an outcome reporting the failed attempt
     */
    public static CmsPurgeWebhookResult failed(String contentType, String event, PurgeAction action,
                                               PurgeNetwork network, String reason) {
        return new CmsPurgeWebhookResult(false, contentType, event, action, network, 0, reason);
    }
}
