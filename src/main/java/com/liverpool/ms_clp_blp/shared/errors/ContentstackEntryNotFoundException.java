package com.liverpool.ms_clp_blp.shared.errors;

/**
 * Thrown when the CMS holds no entry matching a content request, whichever source answered.
 *
 * <p>Source-neutral by design: the content-service says so with a 404 and a direct Contentstack query
 * says so by returning an empty {@code entries} array, and the slices must not care which. Each slice
 * converts this into its own not-found exception — {@code LandingDefinitionNotFoundException} and
 * friends — so the error surfaced to the caller keeps naming the resource that was missing.</p>
 *
 * <p>It lives in {@code shared} rather than a slice because {@code clients} raises it and may never
 * depend on a slice.</p>
 */
public final class ContentstackEntryNotFoundException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail naming the content type, locale and entry that was not found
     */
    public ContentstackEntryNotFoundException(String message, String detail) {
        super(ErrorCodes.LANDING_PAGE_NOT_FOUND, ErrorCategory.RESOURCE_NOT_FOUND, message, detail, null);
    }
}
