package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Thrown when an inbound request fails domain validation (e.g. malformed locale/path/blockId).
 * Maps to HTTP 400 and is the first line of defence against injection (security).
 */
public final class ValidationException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing reason the input was rejected
     * @param detail  technical detail naming the offending field and expected format
     */
    public ValidationException(String message, String detail) {
        super(ErrorCodes.VALIDATION_ERROR, ErrorCategory.VALIDATION, message, detail, null);
    }
}
