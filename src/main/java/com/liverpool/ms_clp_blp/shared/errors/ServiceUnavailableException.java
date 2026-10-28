package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Thrown when a circuit breaker is open and a call is short-circuited. Returns HTTP 503 with the
 * stable {@code SERVICE_UNAVAILABLE} code and the agreed consumer message — kept distinct from a
 * backing-service error so the frontend can attribute the failure to load-shedding, not the service.
 */
public final class ServiceUnavailableException extends ClpBlpException {

    private static final int HTTP_SERVICE_UNAVAILABLE = 503;
    private static final String CONSUMER_MESSAGE = "Service not available at this moment.";

    /**
     * @param detail technical detail (which breaker opened, for which dependency)
     * @param cause  the breaker's {@code CallNotPermittedException} (may be null)
     */
    public ServiceUnavailableException(String detail, Throwable cause) {
        super(ErrorCodes.SERVICE_UNAVAILABLE, ErrorCategory.EXTERNAL_SERVICE, HTTP_SERVICE_UNAVAILABLE,
                CONSUMER_MESSAGE, detail, true, cause);
    }
}
