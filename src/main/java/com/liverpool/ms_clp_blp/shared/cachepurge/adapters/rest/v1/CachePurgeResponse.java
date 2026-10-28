package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.rest.v1;

import java.util.List;

import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeReport;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a purge run submitted, and what the CDN said about each call.
 *
 * <p>Deliberately echoes the objects back. An operator firing this mid-incident needs to see
 * <em>what</em> was purged, not just that something was — a misconfigured
 * {@code CACHE_PURGE_SITE_BASE_URL} otherwise produces a perfectly successful purge of the wrong
 * domain.</p>
 *
 * @param action       the action applied to every call in this run
 * @param network      the network purged
 * @param totalObjects how many URLs and tags were purged in total
 * @param purges       one entry per call the run made
 */
public record CachePurgeResponse(
        @Schema(description = "The action applied.", example = "INVALIDATE")
        String action,
        @Schema(description = "The Akamai network purged.", example = "PRODUCTION")
        String network,
        @Schema(description = "URLs and tags purged across every call in this run.", example = "4")
        int totalObjects,
        @Schema(description = "One entry per call submitted to the CDN.")
        List<PurgeDetail> purges) {

    /**
     * One submitted purge.
     *
     * @param targetKind       {@code URL} or {@code TAG}
     * @param objects          the URLs or tags submitted
     * @param purgeId          Akamai's id for this purge
     * @param supportId        id to quote to Akamai Technical Support
     * @param estimatedSeconds Akamai's estimate of propagation time
     */
    public record PurgeDetail(
            @Schema(description = "Whether these objects are URLs or cache tags.", example = "URL")
            String targetKind,
            @Schema(description = "The URLs or cache tags submitted.")
            List<String> objects,
            @Schema(description = "Akamai's identifier for this purge.",
                    example = "e535071c-26b2-11e7-94d7-276f2f54d938")
            String purgeId,
            @Schema(description = "Identifier to quote to Akamai Technical Support.",
                    example = "17PY1234567890123456-123456789")
            String supportId,
            @Schema(description = "Akamai's estimate of propagation time, in seconds.", example = "5")
            Integer estimatedSeconds) {
    }

    /**
     * @param report the domain outcome of the run; never empty in practice, since a run with nothing
     *               configured to purge produces no calls and therefore no report entries
     * @return the wire shape of that outcome
     */
    public static CachePurgeResponse from(PurgeReport report) {
        List<PurgeDetail> details = report.outcomes().stream()
                .map(CachePurgeResponse::detailOf)
                .toList();
        PurgeOutcome first = report.outcomes().isEmpty() ? null : report.outcomes().get(0);
        return new CachePurgeResponse(
                first == null ? null : first.action().name(),
                first == null ? null : first.network().name(),
                report.totalObjects(),
                details);
    }

    private static PurgeDetail detailOf(PurgeOutcome outcome) {
        return new PurgeDetail(outcome.kind().name(), outcome.objects(), outcome.purgeId(),
                outcome.supportId(), outcome.estimatedSeconds());
    }
}
