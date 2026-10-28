package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Centralised exception categories with default metadata (status + retryability) to remove
 * duplication across concrete exceptions (logger-handler skill). Concrete exceptions may override the
 * default status when a category spans several HTTP semantics.
 */
public enum ErrorCategory {

    VALIDATION(400, false),
    /**
     * The caller presented no credential, or the wrong one. Never retryable with the same credential —
     * retrying is only worth it after the caller is reconfigured, which no client can decide for itself.
     */
    AUTHENTICATION(401, false),
    BUSINESS(422, false),
    RESOURCE_NOT_FOUND(404, false),
    EXTERNAL_SERVICE(502, true),
    TIMEOUT(504, true),
    CONFIGURATION(500, false),
    DATABASE(500, true),
    INFRASTRUCTURE(500, true),
    UNEXPECTED(500, false);

    private final int defaultStatus;
    private final boolean defaultRetryable;

    ErrorCategory(int defaultStatus, boolean defaultRetryable) {
        this.defaultStatus = defaultStatus;
        this.defaultRetryable = defaultRetryable;
    }

    public int defaultStatus() {
        return defaultStatus;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }
}
