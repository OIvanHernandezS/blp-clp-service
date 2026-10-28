package com.liverpool.ms_clp_blp.shared.cachepurge.errors;

import com.liverpool.ms_clp_blp.shared.errors.ClpBlpException;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCategory;
import com.liverpool.ms_clp_blp.shared.errors.ErrorCodes;

/**
 * Thrown when a purge is requested but the Akamai EdgeGrid credential is incomplete.
 *
 * <p>Raised <em>before</em> anything leaves the process. Sending an unsigned or half-signed request
 * would come back as a 403 indistinguishable from a revoked or mistyped credential, sending an
 * operator hunting Akamai-side for a problem that is a missing deployment variable.</p>
 *
 * <p>The detail names the environment variables, never their values — this message reaches the
 * response body outside production and the log line everywhere.</p>
 */
public final class CachePurgeNotConfiguredException extends ClpBlpException {

    private static final String CONSUMER_MESSAGE =
            "Cache purge is not configured; no purge was attempted.";
    private static final String DETAIL =
            "Akamai EdgeGrid credentials are incomplete: set AKAMAI_HOST, AKAMAI_CLIENT_TOKEN, "
                    + "AKAMAI_CLIENT_SECRET and AKAMAI_ACCESS_TOKEN. Fast Purge has no username/password "
                    + "mode, so all four are required.";

    public CachePurgeNotConfiguredException() {
        super(ErrorCodes.CACHE_PURGE_NOT_CONFIGURED, ErrorCategory.CONFIGURATION, CONSUMER_MESSAGE,
                DETAIL, null);
    }
}
