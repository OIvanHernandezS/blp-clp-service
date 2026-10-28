package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.akamai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.liverpool.ms_clp_blp.clients.akamai.AkamaiProperties;
import com.liverpool.ms_clp_blp.clients.akamai.AkamaiPurgeClient;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeRequest;
import com.liverpool.ms_clp_blp.clients.akamai.dto.AkamaiPurgeResponse;
import com.liverpool.ms_clp_blp.shared.cachepurge.errors.CachePurgeFailedException;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeOutcome;
import com.liverpool.ms_clp_blp.shared.cachepurge.models.PurgeRequest;
import com.liverpool.ms_clp_blp.shared.cachepurge.ports.CachePurgePort;
import com.liverpool.ms_clp_blp.shared.errors.Causes;
import com.liverpool.ms_clp_blp.shared.logging.LogEvents;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

import feign.FeignException;
import feign.RetryableException;

/**
 * Submits purges to Akamai's Fast Purge v3 API and translates its failures into domain errors.
 *
 * <p><strong>No circuit breaker</strong>, unlike every other outbound adapter in this codebase. The
 * others sit on the request path and must fail fast for a waiting user; a purge is rare,
 * operator-triggered, and off the request path — and an operator purging <em>during</em> an incident
 * is precisely when a breaker would be open and would refuse the call that fixes it. Timeouts and the
 * translation below carry the load instead.</p>
 *
 * <p>{@link RetryableException} is caught alongside {@link FeignException} on purpose: it does not
 * extend the status-carrying {@code FeignException} types, so a {@code catch (FeignException)} alone
 * silently lets every connect timeout and DNS failure escape as an untyped error — the trap already
 * documented on {@code ProductsListVertexAdapterTest}.</p>
 *
 * <p>Nothing logged here can carry a credential: the fields are the purge target, the status and
 * Akamai's own correlation ids. The signed {@code Authorization} header never leaves
 * {@code AkamaiEdgeGridInterceptor}, and the outbound curl line masks it independently.</p>
 */
@Component
public class AkamaiCachePurgeAdapter implements CachePurgePort {

    private static final Logger log = LoggerFactory.getLogger(AkamaiCachePurgeAdapter.class);

    private static final String UPSTREAM = "akamaipurge";
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    /** Akamai's own error bodies are short; this only guards against a proxy returning an HTML page. */
    private static final int MAX_BODY_CHARS = 500;

    private final AkamaiPurgeClient akamaiPurgeClient;
    private final AkamaiProperties akamaiProperties;

    /**
     * @param akamaiPurgeClient the Fast Purge transport
     * @param akamaiProperties  EdgeGrid credentials, read only to answer {@link #configured()}
     */
    public AkamaiCachePurgeAdapter(AkamaiPurgeClient akamaiPurgeClient, AkamaiProperties akamaiProperties) {
        this.akamaiPurgeClient = akamaiPurgeClient;
        this.akamaiProperties = akamaiProperties;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean configured() {
        return akamaiProperties.configured();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PurgeOutcome purge(PurgeRequest request) {
        LogEvents.at(log, LogEvents.CACHE_PURGE_REQUESTED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.PURGE_TARGET_KIND, request.kind().name())
                .addKeyValue(LogFields.PURGE_ACTION, request.action().name())
                .addKeyValue(LogFields.PURGE_NETWORK, request.network().name())
                .addKeyValue(LogFields.PURGE_OBJECTS, String.join(",", request.objects()))
                .addKeyValue(LogFields.PURGE_OBJECT_COUNT, request.objects().size())
                .log("Submitting edge-cache purge to Akamai");

        long startedAt = System.nanoTime();
        try {
            AkamaiPurgeResponse response = akamaiPurgeClient.purge(
                    request.action().wireValue(),
                    request.kind().wireValue(),
                    request.network().wireValue(),
                    new AkamaiPurgeRequest(request.objects()));
            return succeeded(request, response, elapsedMs(startedAt));
        } catch (RetryableException e) {
            throw timedOut(request, e, elapsedMs(startedAt));
        } catch (FeignException e) {
            throw rejected(request, e, elapsedMs(startedAt));
        }
    }

    /**
     * Validates the accepted-purge envelope and records it.
     *
     * <p>A 2xx with no {@code purgeId} is treated as a failure: without it there is nothing to quote
     * to Akamai, and it is the signal that something other than Akamai answered — a proxy, a captive
     * portal, or a misrouted host.</p>
     *
     * @param request   what was submitted
     * @param response  what Akamai answered
     * @param elapsedMs how long the call took
     * @return the domain outcome
     */
    private PurgeOutcome succeeded(PurgeRequest request, AkamaiPurgeResponse response, long elapsedMs) {
        if (response == null || response.purgeId() == null || response.purgeId().isBlank()) {
            CachePurgeFailedException failure = CachePurgeFailedException.upstream(
                    "The CDN accepted the purge but did not identify it.",
                    "Akamai answered 2xx without a purgeId, so the purge cannot be tracked. This usually "
                            + "means something other than Akamai answered — check akamai.base-url.",
                    response == null ? null : response.httpStatus(),
                    response == null ? null : response.supportId(),
                    null);
            logFailure(request, failure, response == null ? null : response.httpStatus(), elapsedMs);
            throw failure;
        }
        LogEvents.at(log, LogEvents.CACHE_PURGE_SUCCEEDED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.PURGE_TARGET_KIND, request.kind().name())
                .addKeyValue(LogFields.PURGE_ACTION, request.action().name())
                .addKeyValue(LogFields.PURGE_NETWORK, request.network().name())
                .addKeyValue(LogFields.PURGE_OBJECT_COUNT, request.objects().size())
                .addKeyValue(LogFields.PURGE_ID, response.purgeId())
                .addKeyValue(LogFields.PURGE_SUPPORT_ID, response.supportId())
                .addKeyValue(LogFields.PURGE_ESTIMATED_SECONDS, response.estimatedSeconds())
                .addKeyValue(LogFields.HTTP_STATUS, response.httpStatus())
                .addKeyValue(LogFields.ELAPSED_MS, elapsedMs)
                .log("Akamai accepted the edge-cache purge");
        return new PurgeOutcome(request.kind(), request.action(), request.network(), request.objects(),
                response.purgeId(), response.supportId(), response.estimatedSeconds());
    }

    /**
     * Translates an HTTP failure, separating the ones an operator can act on.
     *
     * @param request   what was submitted
     * @param e         the upstream failure
     * @param elapsedMs how long the call took
     * @return the exception to throw
     */
    private CachePurgeFailedException rejected(PurgeRequest request, FeignException e, long elapsedMs) {
        int status = e.status();
        String message = switch (status) {
            case HTTP_FORBIDDEN -> "The CDN rejected the purge credential.";
            case HTTP_TOO_MANY_REQUESTS -> "The CDN is rate limiting purges; try again shortly.";
            default -> "The CDN rejected the purge.";
        };
        String detail = switch (status) {
            case HTTP_FORBIDDEN -> "Akamai answered 403. Either an object belongs to a CP code (or, for "
                    + "tags, a scope) this API client is not authorised to purge — check that the URLs "
                    + "are on the host Akamai serves this service on, not another property — or the "
                    + "EdgeGrid credential is wrong, expired, or lacks the Fast Purge scope for this "
                    + "network; the host must also match the one the credential was issued for. Akamai "
                    + "said: " + truncate(e.contentUTF8());
            case HTTP_TOO_MANY_REQUESTS -> "Akamai answered 429 (Fast Purge is rate limited). Not "
                    + "retried automatically: the earlier purge may already have been accepted. Akamai "
                    + "said: " + truncate(e.contentUTF8());
            default -> "Akamai answered " + status + ". Akamai said: " + truncate(e.contentUTF8());
        };
        CachePurgeFailedException failure =
                CachePurgeFailedException.upstream(message, detail, status, null, e);
        logFailure(request, failure, status, elapsedMs);
        return failure;
    }

    /**
     * Translates a timeout or a connectivity failure — nothing answered, so there is no status.
     *
     * @param request   what was submitted
     * @param e         the transport failure
     * @param elapsedMs how long the call took before giving up
     * @return the exception to throw
     */
    private CachePurgeFailedException timedOut(PurgeRequest request, RetryableException e, long elapsedMs) {
        CachePurgeFailedException failure = CachePurgeFailedException.timeout(
                "The CDN did not respond to the purge in time.",
                "Akamai did not answer within akamai.read-timeout, or could not be reached at all. The "
                        + "purge may still have been accepted, so re-running it is safe but not "
                        + "guaranteed to be necessary. Root cause: " + Causes.rootMessage(e),
                e);
        logFailure(request, failure, null, elapsedMs);
        return failure;
    }

    private void logFailure(PurgeRequest request, CachePurgeFailedException failure, Integer status,
                            long elapsedMs) {
        LogEvents.at(log, LogEvents.CACHE_PURGE_FAILED)
                .addKeyValue(LogFields.UPSTREAM, UPSTREAM)
                .addKeyValue(LogFields.PURGE_TARGET_KIND, request.kind().name())
                .addKeyValue(LogFields.PURGE_ACTION, request.action().name())
                .addKeyValue(LogFields.PURGE_NETWORK, request.network().name())
                .addKeyValue(LogFields.PURGE_OBJECT_COUNT, request.objects().size())
                .addKeyValue(LogFields.HTTP_STATUS, status)
                .addKeyValue(LogFields.ELAPSED_MS, elapsedMs)
                .addKeyValue(LogFields.ERROR_CLASS, failure.getCause() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getCause().getClass().getSimpleName())
                .addKeyValue(LogFields.CAUSE_CHAIN, Causes.chain(failure))
                .addKeyValue(LogFields.DETAIL, failure.getDetail())
                .log("Edge-cache purge failed; the edge still serves the previous content");
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String truncate(String body) {
        if (body == null || body.isEmpty()) {
            return "(no body)";
        }
        return body.length() <= MAX_BODY_CHARS ? body : body.substring(0, MAX_BODY_CHARS) + "…(truncated)";
    }
}
