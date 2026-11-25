package com.liverpool.ms_clp_blp.landing.adapters.rest.v1;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for {@link CategoryControllerV1} (Rule 8.1). Holds every Swagger annotation and the
 * validation constraints, which Hibernate Validator requires on the interface (HV000151); the routing
 * annotations remain on the implementation. The brand ({@code x-brand-id}) is a shared context header
 * documented globally by {@code OpenApiConfig}.
 */
@Tag(name = "Categories", description = "Reads a category and its children from the category indexer.")
public interface CategoryApiV1 {

    /**
     * Category id: letters, digits, {@code _} and {@code -}, at most 64. It is placed in the indexer's
     * URL path, so anything that could add a segment or a query is refused here.
     */
    String CATEGORY_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$";

    /**
     * Reads one category with its children.
     *
     * @param categoryId the category id, as it appears in the storefront URL
     * @return 200 with the category and its children
     */
    @Operation(
            summary = "Read a category with its children",
            description = "Returns the category from the category indexer with its visible, valid "
                    + "children, in exactly the `categoryData` shape the BLP/CLP pages carry: each node "
                    + "has `id`, `name`, `urlAddress`, `ancestry`, `metadata` (incl. `imageUrl`, "
                    + "`categoryImage`, `viewUrl`, `showpath` and `applyCLPRedirect`, so the storefront "
                    + "can navigate to a child) and `children`; the root also carries `breadcrumb`. "
                    + "The Contentstack `redirectContent` is not included. The brand is read from "
                    + "`x-brand-id`. A 200 is `Cache-Control: public` — it depends only on brand and "
                    + "category, so the edge may share it.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The category and its children.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CategoryResponse.class),
                            examples = @ExampleObject(name = "categoryWithChildren", value = """
                                    {
                                      "id": "CAT5020010",
                                      "name": "Ropa para hombre",
                                      "displayName": "Ropa para hombre",
                                      "urlAddress": "/tienda/ropa-para-hombre/cat5020010",
                                      "hidden": false,
                                      "ancestry": ["cat5020001"],
                                      "metadata": {
                                        "level": 2,
                                        "title": "Ropa para hombre",
                                        "viewUrl": "/tienda/ropa-para-hombre/cat5020010",
                                        "imageUrl": "",
                                        "showpath": "CLP",
                                        "redirects": [],
                                        "categoryId": "CAT5020010",
                                        "applyCLPRedirect": false
                                      },
                                      "children": [
                                        {
                                          "id": "CAT5020011",
                                          "name": "Camisas",
                                          "displayName": "Camisas",
                                          "urlAddress": "/tienda/camisas/cat5020011",
                                          "hidden": false,
                                          "ancestry": ["cat5020001", "cat5020010"],
                                          "metadata": {
                                            "level": 3,
                                            "parent": "cat5020010",
                                            "title": "Camisas",
                                            "viewUrl": "/tienda/camisas/cat5020011",
                                            "imageUrl": "https://ss201.liverpool.com.mx/xl/camisas.jpg",
                                            "showpath": "PLP",
                                            "redirects": [],
                                            "categoryId": "CAT5020011",
                                            "parentShowpath": "CLP",
                                            "applyCLPRedirect": true
                                          },
                                          "children": []
                                        }
                                      ],
                                      "breadcrumb": "home > Ropa para hombre"
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Missing or malformed categoryId.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The indexer gave no category for the id "
                    + "(unknown, or the indexer could not be read): CATEGORY_NOT_FOUND. Never cached.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<CategoryResponse> getCategory(
            @Parameter(description = "Category id, as it appears in the storefront URL.", required = true,
                    example = "cat5020010")
            @NotBlank @Pattern(regexp = CATEGORY_ID_PATTERN) String categoryId);
}
