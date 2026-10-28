package com.liverpool.ms_clp_blp.shared.errors;

import java.util.Map;

/**
 * Base type for all domain exceptions. Self-describing per the logger-handler skill so the single
 * translation point (the {@code @RestControllerAdvice}) needs no per-type logic.
 *
 * <p>Two levels of information: {@link #getMessage()} is concise and consumer-facing (what failed);
 * {@link #getDetail()} is technical and developer-facing (why/where, probable root cause, suggested
 * action) and is only exposed outside production.</p>
 */
public abstract class ClpBlpException extends RuntimeException {

    private final String errorCode;
    private final ErrorCategory category;
    private final int status;
    private final String detail;
    private final boolean retryable;

    /**
     * Full constructor allowing an explicit HTTP status (when a category spans several).
     *
     * @param errorCode stable machine-readable code
     * @param category  error category
     * @param status    HTTP status to return
     * @param message   concise, consumer-facing explanation
     * @param detail    technical, developer-facing explanation
     * @param retryable whether retrying may succeed
     * @param cause     internal root cause (never serialized to clients)
     */
    protected ClpBlpException(String errorCode, ErrorCategory category, int status, String message,
                            String detail, boolean retryable, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.category = category;
        this.status = status;
        this.detail = detail;
        this.retryable = retryable;
    }

    /**
     * Convenience constructor that derives status and retryability from the category defaults.
     *
     * @param errorCode stable machine-readable code
     * @param category  error category (supplies default status/retryable)
     * @param message   concise, consumer-facing explanation
     * @param detail    technical, developer-facing explanation
     * @param cause     internal root cause
     */
    protected ClpBlpException(String errorCode, ErrorCategory category, String message, String detail,
                            Throwable cause) {
        this(errorCode, category, category.defaultStatus(), message, detail, category.defaultRetryable(), cause);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public ErrorCategory getCategory() {
        return category;
    }

    public int getStatus() {
        return status;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /**
     * Extra structured fields describing <em>this</em> failure, keyed by {@link
     * com.liverpool.ms_clp_blp.shared.logging.LogFields} names.
     *
     * <p>Overriding this is what lets the single translation point log a subtype's context
     * (which block, which upstream, which status) without growing an {@code instanceof} chain, and
     * keeps a new exception type's diagnosability a property of the exception rather than of every
     * call site that catches it.</p>
     *
     * @return field name to value; empty by default, never {@code null}
     */
    public Map<String, Object> context() {
        return Map.of();
    }
}
