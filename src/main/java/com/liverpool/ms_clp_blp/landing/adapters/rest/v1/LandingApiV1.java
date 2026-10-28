package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for {@link LandingControllerV1} (Rule 8.1). Holds every Swagger annotation — and
 * the validation constraints, which Hibernate Validator requires on the interface (HV000151) — so the
 * controller stays a thin adapter; the routing annotations remain on the implementation. The shared
 * context headers/cookie are documented globally by {@code OpenApiConfig}.
 */
@Tag(name = "Landing pages", description = "Composes brand (BLP) and category (CLP) landing pages from Contentstack.")
public interface LandingApiV1 {

    /** Locale segment: two lower-case letters, a dash, two lower-case letters ({@code es-mx}). */
    String LOCALE_PATTERN = "^[a-z]{2}-[a-z]{2}$";

    /**
     * Composes a brand landing page.
     *
     * @param locale         content locale, e.g. {@code es-mx}
     * @param path           storefront path after the locale; its last segment is the category id
     * @param eagerOffset    optional override for how many leading dynamic blocks resolve eagerly
     * @param favoriteStore  the {@code favoriteStore} cookie; feeds {@code selected_store.*} analytics only
     * @param request        carries the gateway identity headers (not documented)
     * @return 200 with the composed page
     */
    @Operation(
            summary = "Compose a brand landing page (BLP)",
            description = "Returns the Contentstack `page_blp` entry authored for the URL's last segment "
                    + "(its `category_id`) as a digital_bff superset: entry metadata, the composed "
                    + "`blocks` (plus `top_content`/`bottom_content` when authored) and `categoryData`. "
                    + "Static blocks are fully resolved; `products_list` carousels are inlined for guests "
                    + "and returned as placeholders advertising `/blp/blocks/products-list` otherwise. "
                    + "`globalData` is not included — read it from ms-home `GET /global-data`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Composed brand landing page.",
                    headers = @Header(name = "x-data-origin", description = "Which tier answered this "
                            + "request: ContentStack or caffeine.",
                            schema = @Schema(type = "string", allowableValues = {"ContentStack", "caffeine"})),
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object",
                                    description = "Raw Contentstack entry + blocks + categoryData."),
                            examples = @ExampleObject(name = "composedBlp", value = """
                                    {
                                      "uid": "blt0ff08f5fb7e51522",
                                      "locale": "es-mx",
                                      "category_id": "catst26617625",
                                      "url": "/tienda/estee-lauder/catst26617625",
                                      "blocks": [
                                        { "_content_type_uid": "hero_banner_slider", "uid": "blt1" },
                                        {
                                          "_content_type_uid": "products_list",
                                          "uid": "blt6325",
                                          "source_of_data": "vertex",
                                          "products": [ { "productId": "1190634149" } ],
                                          "status": "AVAILABLE",
                                          "loadStrategy": "EAGER",
                                          "httpMethod": "POST",
                                          "url": "/blp/blocks/products-list",
                                          "ms_params": {}
                                        }
                                      ],
                                      "categoryData": {
                                        "id": "CATST26617625",
                                        "name": "Estee Lauder",
                                        "breadcrumb": "home > Estee Lauder"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Invalid locale, path or brand.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No landing page is authored for the category.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Contentstack could not be read.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<LandingPageResponse> getBrandLandingPage(
            @Parameter(description = "Content locale.", example = "es-mx")
            @Pattern(regexp = LOCALE_PATTERN) String locale,
            @Parameter(description = "Storefront path after the locale; the last segment is the category id.",
                    example = "tienda/esteelauder/catst26617625") String path,
            @Parameter(description = "Override how many leading dynamic blocks resolve eagerly; "
                    + "0 keeps every dynamic block lazy.", example = "3")
            @Min(0) Integer eagerOffset,
            @Parameter(description = "Favourite store id cookie; fills selected_store analytics values.",
                    example = "7") String favoriteStore,
            @Parameter(hidden = true) HttpServletRequest request);

    /**
     * Composes a category landing page.
     *
     * @param locale         content locale, e.g. {@code es-mx}
     * @param path           storefront path after the locale; its last segment is the category id
     * @param eagerOffset    optional override for how many leading dynamic blocks resolve eagerly
     * @param favoriteStore  the {@code favoriteStore} cookie; feeds {@code selected_store.*} analytics only
     * @param request        carries the gateway identity headers (not documented)
     * @return 200 with the composed page
     */
    @Operation(
            summary = "Compose a category landing page (CLP)",
            description = "Same contract as the BLP endpoint, reading the `page_clp` entry and advertising "
                    + "`/clp/blocks/products-list` for lazy carousels.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Composed category landing page.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object",
                                    description = "Raw Contentstack entry + sections + categoryData."))),
            @ApiResponse(responseCode = "400", description = "Invalid locale, path or brand.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "No landing page is authored for the category.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Contentstack could not be read.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<LandingPageResponse> getCategoryLandingPage(
            @Parameter(description = "Content locale.", example = "es-mx")
            @Pattern(regexp = LOCALE_PATTERN) String locale,
            @Parameter(description = "Storefront path after the locale; the last segment is the category id.",
                    example = "tienda/belleza/cat5020010") String path,
            @Parameter(description = "Override how many leading dynamic blocks resolve eagerly; "
                    + "0 keeps every dynamic block lazy.", example = "3")
            @Min(0) Integer eagerOffset,
            @Parameter(description = "Favourite store id cookie; fills selected_store analytics values.",
                    example = "7") String favoriteStore,
            @Parameter(hidden = true) HttpServletRequest request);
}
