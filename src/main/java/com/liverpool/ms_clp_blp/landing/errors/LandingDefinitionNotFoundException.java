package com.liverpool.ms_clp_blp.landing.errors;

import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

/**
 * Thrown when Contentstack holds no landing page for the requested brand, page type and category (404).
 */
public final class LandingDefinitionNotFoundException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail naming the brand/locale/category that was not found
     */
    public LandingDefinitionNotFoundException(String message, String detail) {
        super(ErrorCodes.LANDING_PAGE_NOT_FOUND, ErrorCategory.RESOURCE_NOT_FOUND, message, detail, null);
    }
}
