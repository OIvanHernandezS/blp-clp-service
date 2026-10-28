package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Thrown when the content-service does not host the brand supplied in {@code x-brand-id} (400 from
 * the proxy). The brand registry lives upstream — ms-clp-blp deliberately keeps no allow-list of its
 * own, which would duplicate that configuration and drift from it.
 *
 * <p>Lives in {@code shared} because both content-service adapters ({@code ContentServiceClient} and
 * {@code GlobalDataClient}) raise it; a slice-local copy would violate the no-cross-slice rule.</p>
 *
 * <p>Not retryable: the caller must send a brand the CMS actually hosts. The upstream response body
 * enumerates the valid brands, so it is carried into {@code detail} for diagnosis.</p>
 */
public final class BrandNotSupportedException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail carrying the rejected brand and the upstream response body
     * @param cause   the underlying HTTP 400 error
     */
    public BrandNotSupportedException(String message, String detail, Throwable cause) {
        super(ErrorCodes.BRAND_NOT_SUPPORTED, ErrorCategory.VALIDATION, message, detail, cause);
    }
}
