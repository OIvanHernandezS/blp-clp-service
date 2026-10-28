package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.adapters.rest.v1;

import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the webhook reports back.
 *
 * <p>The status is always 200, so this body is the only place the outcome appears. It is read in
 * Contentstack's delivery log, which is why {@code reason} is prose rather than a code.</p>
 *
 * @param purged      whether a purge was submitted and accepted
 * @param contentType the content type the delivery was about
 * @param event       the Contentstack event that triggered the delivery
 * @param action      what was asked of the CDN, or {@code null} when nothing was
 * @param network     which network was purged, or {@code null} when nothing was
 * @param objectCount how many URLs the purge covered
 * @param reason      why the delivery had the effect it did
 */
@Schema(description = "Outcome of one Contentstack delivery. Always returned with status 200.")
public record CmsPurgeWebhookResponse(
        @Schema(description = "Whether a purge was submitted and the CDN accepted it.", example = "true")
        boolean purged,
        @Schema(description = "Content type the delivery was about.", example = "flex")
        String contentType,
        @Schema(description = "Contentstack event that triggered the delivery.", example = "publish")
        String event,
        @Schema(description = "What was asked of the CDN; absent when no purge was attempted.",
                example = "invalidate", allowableValues = {"invalidate", "delete"})
        String action,
        @Schema(description = "Akamai network that was purged; absent when no purge was attempted.",
                example = "production", allowableValues = {"staging", "production"})
        String network,
        @Schema(description = "How many URLs the purge covered.", example = "3")
        int objectCount,
        @Schema(description = "Human-readable explanation of the outcome.",
                example = "Submitted invalidate for 3 URL(s) on the production network")
        String reason) {

    /**
     * @param result domain outcome
     * @return the wire shape of that outcome, with the enums rendered as the lowercase values Akamai
     *         and the ops endpoint both use
     */
    public static CmsPurgeWebhookResponse from(CmsPurgeWebhookResult result) {
        return new CmsPurgeWebhookResponse(
                result.purged(),
                result.contentType(),
                result.event(),
                result.action() == null ? null : result.action().wireValue(),
                result.network() == null ? null : result.network().wireValue(),
                result.objectCount(),
                result.reason());
    }
}
