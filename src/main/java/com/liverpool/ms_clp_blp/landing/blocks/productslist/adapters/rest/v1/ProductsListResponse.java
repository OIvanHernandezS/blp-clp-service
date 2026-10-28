package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.liverpool.dto.ProductRecommendation;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body for {@code POST …/blocks/products-list}, one row per block.
 *
 * @param blockId  the resolved block id echoed back for correlation
 * @param title    carousel display title
 * @param products ordered list of products in the carousel
 */
/**
 * The resolved carousel, using digital_bff's block-level field names.
 *
 * <p>These names matter because the eager path merges this object straight onto the flat CMS block:
 * the campaign title must land on {@code products_list_title} (the CMS field the legacy strategy
 * overwrites), <strong>not</strong> on {@code title}, which belongs to the block itself.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Resolved carousel using digital_bff's block-level field names.")
public record ProductsListResponse(
        @Schema(description = "Resolved block id, echoed for correlation.",
                example = "blt0a1b2c3d4e5f6a7") String blockId,
        @Schema(description = "Carousel display title (Salesforce campaigns only).",
                example = "Ofertas increíbles")
        @JsonProperty("products_list_title") String productsListTitle,
        @Schema(description = "Campaign id (Salesforce campaigns only).", example = "CMSOfertasIncreibles")
        @JsonProperty("productsListId") String productsListId,
        @Schema(description = "Resolved products (digital_bff ProductRecommendation shape).")
        List<ProductRecommendation> products) {
}
