package com.liverpool.ms_clp_blp.landing.blocks.productslist.adapters.rest.v1;

import java.util.List;
import java.util.Map;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for {@link ProductsListControllerV1} (Rule 8.1). Documents the single, batch,
 * provider-agnostic {@code products_list} resolution endpoint (ADR-029); shared context
 * headers/cookie are documented globally by {@code OpenApiConfig}.
 */
@Tag(name = "Block: products-list",
        description = "Resolves products_list carousels for every provider (Jewel, Salesforce, Vertex).")
public interface ProductsListApiV1 {

    /** Bean-validation bound requires the constraint to live on the interface method (HV000151). */
    int MAX_BLOCKS_PER_REQUEST = 50;

    /**
     * @param blocks the block objects received from the landing page ({@code GET /blp/**}, {@code GET /clp/**}); each carries a {@code uid} and a
     *               {@code source_of_data} that selects the provider
     * @return 200 with one entry per requested block, in request order
     */
    @Operation(
            summary = "Resolve products-list carousels (batch)",
            description = "Takes the JSON array of block objects `GET /blp/**` / `GET /clp/**` returned and resolves each "
                    + "carousel concurrently. The provider is chosen per block from its own "
                    + "`source_of_data`, using the settings the block carries (`jewel_model_config`, "
                    + "`salesforce_carousel`, `min_of_products`/`max_of_products`). Partial failure is "
                    + "expected: each entry carries its own `status`, so one provider outage degrades "
                    + "only its blocks and the call still answers 200. Only a total failure surfaces as "
                    + "an RFC 7807 problem. One block is an array of one — there is no single-block "
                    + "route.\n\nBranch on `status` per entry: `AVAILABLE` carries `content` to draw; "
                    + "`EMPTY` means the carousel resolved legitimately to nothing, so drop the block "
                    + "and **do not retry**; `UNAVAILABLE` means that provider failed, so draw the "
                    + "fallback and retry later. `reason` names the rule behind the latter two and is "
                    + "diagnostic only — it is the same value the service logs, so a frontend report and "
                    + "a Cloud Logging query use the same word (ADR-043).\n\n"
                    + "A block may carry an A/B `conditional_show_hide` rule (`active`, `action` = "
                    + "`show`/`hide`, `header_name`, `cookie_name`). While the `conditional-show-hide` flag "
                    + "is on for the brand, the named request header — or, failing it, the cookie — must "
                    + "carry literally `true` or `false`: `show` keeps the block only for `true`, `hide` "
                    + "only for `false`, and a missing or unreadable marker drops it. A dropped cohort is "
                    + "`EMPTY` with `CONDITIONAL_SHOW_HIDE_EXCLUDED` or `CONDITIONAL_SHOW_HIDE_COHORT_UNKNOWN`, "
                    + "and a response carrying such a block is never edge-cacheable (ADR-060).")
    @RequestBody(required = true, description = "Array of block objects from `GET /blp/**` / `GET /clp/**` (max 50).",
            content = @Content(mediaType = "application/json",
                    examples = @ExampleObject(name = "salesforceAndJewel", value = """
                            [
                              {
                                "uid": "blt0a1b2c3d4e5f6a7",
                                "source_of_data": "salesforce",
                                "salesforce_carousel": "CMSOfertasIncreibles",
                                "min_of_products": 3,
                                "max_of_products": 15
                              },
                              {
                                "uid": "blt7a6f5e4d3c2b1a09",
                                "source_of_data": "jewel",
                                "jewel_model_config": { "model": "also_bought", "requires_user": true },
                                "max_of_products": 12,
                                "conditional_show_hide": {
                                  "active": true,
                                  "action": "show",
                                  "header_name": "x-ab-carousel"
                                }
                              }
                            ]""")))
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "One entry per block, in request order (statuses may differ).",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ProductsListBatchResponse.class),
                            examples = @ExampleObject(name = "everyOutcome", value = """
                                    {
                                      "blocks": [
                                        {
                                          "blockId": "blt0a1b2c3d4e5f6a7",
                                          "status": "AVAILABLE",
                                          "content": {
                                            "blockId": "blt0a1b2c3d4e5f6a7",
                                            "products_list_title": "Ofertas increíbles",
                                            "productsListId": "CMSOfertasIncreibles",
                                            "products": [
                                              {
                                                "productId": "1043210",
                                                "name": "Licuadora Oster",
                                                "brand": "Oster",
                                                "priceInfo": {
                                                  "price": 1299.0,
                                                  "originalPrice": 1299.0,
                                                  "promoPrice": 899.0
                                                },
                                                "images": ["https://sp632.liverpool.com.mx/i/1043210.jpg"],
                                                "categories": ["Electrodomésticos"],
                                                "index": 0
                                              }
                                            ]
                                          }
                                        },
                                        {
                                          "blockId": "blt1b2c3d4e5f6a7b8",
                                          "status": "EMPTY",
                                          "reason": "BELOW_MIN_PRODUCTS"
                                        },
                                        {
                                          "blockId": "blt2c3d4e5f6a7b8c9",
                                          "status": "EMPTY",
                                          "reason": "UPSTREAM_RETURNED_NO_PRODUCTS"
                                        },
                                        {
                                          "blockId": "blt7a6f5e4d3c2b1a09",
                                          "status": "UNAVAILABLE",
                                          "reason": "UPSTREAM_FAILED"
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Empty or oversized (>50) block array.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Total resolution failure.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<ProductsListBatchResponse> resolveProductsList(
            @NotEmpty @Size(max = MAX_BLOCKS_PER_REQUEST) List<Map<String, Object>> blocks);
}
