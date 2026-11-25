package com.liverpool.ms_clp_blp.landing.errors;

import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

/**
 * Thrown when the category indexer gives no category for the requested brand and id (404).
 *
 * <p>The category port is best effort, so an unknown category and an unreachable indexer both end
 * here; the indexer failure itself is already logged as {@code CATEGORY_UNAVAILABLE}. Either way the
 * response is an error, which never carries the shared {@code Cache-Control} and so is never held at
 * the edge.</p>
 */
public final class CategoryNotFoundException extends ClpBlpException {

    /**
     * @param message concise, consumer-facing message
     * @param detail  technical detail naming the brand and category that were not found
     */
    public CategoryNotFoundException(String message, String detail) {
        super(ErrorCodes.CATEGORY_NOT_FOUND, ErrorCategory.RESOURCE_NOT_FOUND, message, detail, null);
    }
}
