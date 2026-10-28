package com.liverpool.ms_clp_blp.landing.brandflags.models;

/**
 * Outcome of one Contentstack webhook delivery.
 *
 * <p>Returned with {@code 200} even when {@code applied} is false. A webhook that is not for us, or
 * that carried nothing usable, is not an error the CMS should retry — and Contentstack retries every
 * non-2xx, so answering with a failure status turns an irrelevant event into a retry storm. The
 * {@code reason} is what tells an operator which of those happened.</p>
 *
 * @param applied whether the stored configuration changed
 * @param action  what was attempted: {@code upsert}, {@code delete} or {@code ignored}
 * @param brand   the brand the delivery was about, when one could be determined
 * @param reason  short, human-readable explanation — the field an operator reads first
 */
public record BrandFlagsSyncResult(
        boolean applied,
        String action,
        String brand,
        String reason) {

    /** A delivery this service does not act on. */
    public static final String ACTION_IGNORED = "ignored";

    /** A publish that wrote the brand's configuration. */
    public static final String ACTION_UPSERT = "upsert";

    /** An unpublish or delete that removed it, returning the brand to its defaults. */
    public static final String ACTION_DELETE = "delete";

    /**
     * @param brand  brand the delivery named, may be {@code null}
     * @param reason why nothing was done
     * @return an unapplied result
     */
    public static BrandFlagsSyncResult ignored(String brand, String reason) {
        return new BrandFlagsSyncResult(false, ACTION_IGNORED, brand, reason);
    }
}
