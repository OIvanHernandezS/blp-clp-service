package com.liverpool.ms_clp_blp.shared.cachepurge.errors;

import java.util.HashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;
import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Thrown when the CDN rejected a purge, could not be reached, timed out, or answered something
 * unusable.
 *
 * <p>Carries the upstream status and Akamai's support id through {@link #context()}, so
 * {@code GlobalExceptionHandler} logs them without growing an {@code instanceof} chain — the
 * mechanism {@code ClpBlpException#context()} exists for.</p>
 *
 * <p>Nothing here ever holds a credential. The messages are built from the upstream status and
 * Akamai's own response text; the signed {@code Authorization} header is not part of any of them.</p>
 */
public final class CachePurgeFailedException extends ClpBlpException {

    private static final int HTTP_BAD_GATEWAY = 502;
    private static final String UPSTREAM = "akamaipurge";

    private final transient Integer upstreamStatus;
    private final transient String supportId;

    /**
     * @param message        concise, consumer-facing message
     * @param detail         technical detail (status, upstream body, probable cause)
     * @param category       {@link ErrorCategory#EXTERNAL_SERVICE} for a rejection or an unusable
     *                       answer, {@link ErrorCategory#TIMEOUT} for a timeout or connectivity failure
     * @param status         HTTP status to return: 502 for a rejection, 504 for a timeout
     * @param retryable      whether firing the purge again may work
     * @param upstreamStatus the CDN's HTTP status, or {@code null} when nothing answered
     * @param supportId      Akamai's support id when it sent one
     * @param cause          the underlying transport or HTTP error
     */
    public CachePurgeFailedException(String message, String detail, ErrorCategory category, int status,
                                     boolean retryable, Integer upstreamStatus, String supportId,
                                     Throwable cause) {
        super(ErrorCodes.CACHE_PURGE_FAILED, category, status, message, detail, retryable, cause);
        this.upstreamStatus = upstreamStatus;
        this.supportId = supportId;
    }

    /**
     * Rejection, unreachable host, or an answer that could not be used.
     *
     * @param message        concise, consumer-facing message
     * @param detail         technical detail
     * @param upstreamStatus the CDN's HTTP status, or {@code null} when nothing answered
     * @param supportId      Akamai's support id when it sent one
     * @param cause          the underlying error
     * @return a 502 failure
     */
    public static CachePurgeFailedException upstream(String message, String detail, Integer upstreamStatus,
                                                     String supportId, Throwable cause) {
        return new CachePurgeFailedException(message, detail, ErrorCategory.EXTERNAL_SERVICE,
                HTTP_BAD_GATEWAY, ErrorCategory.EXTERNAL_SERVICE.defaultRetryable(), upstreamStatus,
                supportId, cause);
    }

    /**
     * The purge did not get an answer in time, or never reached the CDN at all.
     *
     * @param message concise, consumer-facing message
     * @param detail  technical detail
     * @param cause   the timeout or connectivity error
     * @return a 504 failure
     */
    public static CachePurgeFailedException timeout(String message, String detail, Throwable cause) {
        return new CachePurgeFailedException(message, detail, ErrorCategory.TIMEOUT,
                ErrorCategory.TIMEOUT.defaultStatus(), ErrorCategory.TIMEOUT.defaultRetryable(), null,
                null, cause);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Map<String, Object> context() {
        Map<String, Object> context = new HashMap<>();
        context.put(LogFields.UPSTREAM, UPSTREAM);
        if (upstreamStatus != null) {
            context.put(LogFields.UPSTREAM_STATUS, upstreamStatus);
        }
        if (supportId != null) {
            context.put(LogFields.PURGE_SUPPORT_ID, supportId);
        }
        return context;
    }
}
