package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.liverpool.ms_clp_blp.landing.models.BlockDropReason;
import com.liverpool.ms_clp_blp.landing.models.DynamicBlockStatus;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST /blp/blocks/products-list} (or {@code /clp/…}).
 *
 * <p>Always an envelope around a list, even for a single block, so there is one contract and one code
 * path (ADR-029). Entries come back in request order.</p>
 *
 * @param blocks one entry per requested block
 */
@Schema(description = "Batch envelope: one entry per requested block, in request order.")
public record ProductsListBatchResponse(
        @Schema(description = "One entry per requested block, in request order.") List<Item> blocks) {

    public ProductsListBatchResponse {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    /**
     * One resolved block.
     *
     * <p>{@code status} is per block on purpose: a provider outage degrades its own carousels to
     * {@link DynamicBlockStatus#UNAVAILABLE} while the rest of the batch still resolves, so the whole
     * call does not fail on one bad upstream. The HTTP status stays 200 in that case — only a total
     * failure surfaces as an RFC 7807 problem via {@code GlobalExceptionHandler}.</p>
     *
     * <p>Three outcomes, and {@code status} alone separates them — the frontend never has to infer one
     * from the absence of {@code content} (ADR-043):</p>
     * <ul>
     *   <li>{@link DynamicBlockStatus#AVAILABLE} — {@code content} is present; draw it.</li>
     *   <li>{@link DynamicBlockStatus#EMPTY} — resolved legitimately to nothing; drop the block and
     *       <strong>do not retry</strong>. {@code reason} names the rule.</li>
     *   <li>{@link DynamicBlockStatus#UNAVAILABLE} — the provider failed; draw the fallback and retry
     *       later.</li>
     * </ul>
     *
     * @param blockId the block this entry answers, echoed for correlation
     * @param status  which of the three outcomes above this entry is
     * @param reason  why the block is not {@code AVAILABLE}; omitted when it is
     * @param content the resolved carousel; {@code null} unless {@code status} is
     *                {@link DynamicBlockStatus#AVAILABLE}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "One resolved block; content is present only when status is AVAILABLE.")
    public record Item(
            @Schema(description = "The block this entry answers, echoed for correlation.",
                    example = "blt0a1b2c3d4e5f6a7") String blockId,
            @Schema(description = "AVAILABLE when there is content to draw; EMPTY when resolution "
                    + "succeeded but produced nothing (drop the block, never retry); UNAVAILABLE when "
                    + "the backing service failed (draw the fallback, retry later).",
                    example = "AVAILABLE") DynamicBlockStatus status,
            @Schema(description = "Why the block is not AVAILABLE. Diagnostic only — branch on status, "
                    + "not on this. Omitted when status is AVAILABLE.",
                    example = "BELOW_MIN_PRODUCTS") BlockDropReason reason,
            @Schema(description = "The resolved carousel; omitted unless status is AVAILABLE.")
            ProductsListResponse content) {
    }
}
