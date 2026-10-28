package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Thrown when the content-service proxy cannot be reached or returns an unexpected failure. Preserves
 * the root cause via chaining (Rule 12) and is retryable (transient external failure).
 */
public final class ContentServiceUnavailableException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail (endpoint, status, probable cause)
     * @param cause   underlying I/O or HTTP error
     */
    public ContentServiceUnavailableException(String message, String detail, Throwable cause) {
        super(ErrorCodes.CONTENT_SERVICE_UNAVAILABLE, ErrorCategory.EXTERNAL_SERVICE, message, detail, cause);
    }
}
