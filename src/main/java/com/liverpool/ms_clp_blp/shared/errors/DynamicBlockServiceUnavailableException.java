package com.liverpool.ms_clp_blp.shared.errors;

import java.util.LinkedHashMap;
import java.util.Map;

import com.liverpool.ms_clp_blp.shared.logging.LogFields;

/**
 * Thrown by a dynamic block's resolution endpoint when its backing service is unhealthy. Distinct
 * from a runtime-disabled block and from a tripped circuit breaker — carries the block id/type so the
 * frontend can mark exactly that block unavailable while rendering the rest of the page.
 *
 * <p>It also carries which upstream failed and with what status. Every adapter already has both in
 * hand inside its {@code catch (FeignException e)}, and without them a degraded block logs the same
 * line whether Salesforce timed out or Jewel answered 401 — the two failures that need entirely
 * different responses from an operator.</p>
 */
public final class DynamicBlockServiceUnavailableException extends ClpBlpException {

    /** Feign reports {@code 0} when the call never got a response (connect/read timeout, DNS). */
    private static final int NO_HTTP_STATUS = 0;

    private final String blockId;
    private final String blockType;
    private final String upstream;
    private final int upstreamStatus;

    /**
     * @param blockId   the block being resolved; {@code null} for an endpoint addressed by session
     *                  alone, where no block id is an input to the resolution
     * @param blockType the block's type
     * @param detail    technical detail (downstream endpoint, status, cause)
     * @param cause     underlying error (may be null)
     */
    public DynamicBlockServiceUnavailableException(String blockId, String blockType, String detail,
                                                   Throwable cause) {
        this(blockId, blockType, null, NO_HTTP_STATUS, detail, cause);
    }

    /**
     * @param blockId        the block being resolved; {@code null} when addressed by session alone
     * @param blockType      the block's type
     * @param upstream       the external system that failed ({@code salesforce}, {@code jewel},
     *                       {@code vertex}); {@code null} when the failure is not attributable to one
     * @param upstreamStatus the HTTP status it answered, or {@code 0} when no response was received
     * @param detail         technical detail (downstream endpoint, status, cause)
     * @param cause          underlying error (may be null)
     */
    public DynamicBlockServiceUnavailableException(String blockId, String blockType, String upstream,
                                                   int upstreamStatus, String detail, Throwable cause) {
        super(ErrorCodes.BLOCK_SERVICE_UNAVAILABLE, ErrorCategory.EXTERNAL_SERVICE,
                "The requested block could not be resolved at this moment.", detail, cause);
        this.blockId = blockId;
        this.blockType = blockType;
        this.upstream = upstream;
        this.upstreamStatus = upstreamStatus;
    }

    public String getBlockId() {
        return blockId;
    }

    public String getBlockType() {
        return blockType;
    }

    public String getUpstream() {
        return upstream;
    }

    /**
     * @return the upstream HTTP status, or {@code 0} when the call never received a response
     */
    public int getUpstreamStatus() {
        return upstreamStatus;
    }

    @Override
    public Map<String, Object> context() {
        Map<String, Object> context = new LinkedHashMap<>();
        // A null id is meaningful here (container_greeting is addressed by session alone), so it is
        // omitted rather than logged as "null", matching how the ProblemDetail treats it.
        if (blockId != null) {
            context.put(LogFields.BLOCK_ID, blockId);
        }
        context.put(LogFields.BLOCK_TYPE, blockType);
        if (upstream != null) {
            context.put(LogFields.UPSTREAM, upstream);
        }
        if (upstreamStatus != NO_HTTP_STATUS) {
            context.put(LogFields.UPSTREAM_STATUS, upstreamStatus);
        }
        return context;
    }
}
