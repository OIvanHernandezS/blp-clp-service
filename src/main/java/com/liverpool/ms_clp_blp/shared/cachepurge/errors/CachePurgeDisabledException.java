package com.liverpool.ms_clp_blp.shared.cachepurge.errors;

import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

/**
 * Thrown when {@code POST /ops/cache-purge**} is called while {@code cache-purge.enabled} is false.
 *
 * <p>409 rather than a silent no-op 200, for the same reason {@code EntryBackupDisabledException}
 * chooses it: an operator hitting this endpoint is acting deliberately, usually mid-incident, and an
 * empty success would read as "the purge ran and found nothing" instead of "nothing was attempted".</p>
 */
public final class CachePurgeDisabledException extends ClpBlpException {

    private static final int HTTP_CONFLICT = 409;
    private static final String CONSUMER_MESSAGE = "Cache purge is disabled; no purge was attempted.";

    public CachePurgeDisabledException() {
        super(ErrorCodes.CACHE_PURGE_DISABLED, ErrorCategory.BUSINESS, HTTP_CONFLICT, CONSUMER_MESSAGE,
                "cache-purge.enabled is false", false, null);
    }
}
