package com.liverpool.ms_clp_blp.landing.brandflags.adapters.rest.v1;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What the webhook reports back. Read by whoever is looking at the delivery log in Contentstack, so
 * {@code reason} is prose rather than a code.
 *
 * @param applied whether the stored configuration changed
 * @param action  {@code upsert}, {@code delete} or {@code ignored}
 * @param brand   the brand the delivery was about
 * @param reason  why the delivery had the effect it did
 */
public record BrandFlagsSyncResponse(
        @Schema(description = "Whether the stored configuration changed.", example = "true")
        boolean applied,
        @Schema(description = "What was attempted.", example = "upsert",
                allowableValues = {"upsert", "delete", "ignored"})
        String action,
        @Schema(description = "Brand the delivery was about.", example = "GAP")
        String brand,
        @Schema(description = "Human-readable explanation of the outcome.",
                example = "Stored 3 flag override(s)")
        String reason) {

    /**
     * @param result domain outcome
     * @return the wire shape of that outcome
     */
    public static BrandFlagsSyncResponse from(BrandFlagsSyncResult result) {
        return new BrandFlagsSyncResponse(
                result.applied(), result.action(), result.brand(), result.reason());
    }
}
