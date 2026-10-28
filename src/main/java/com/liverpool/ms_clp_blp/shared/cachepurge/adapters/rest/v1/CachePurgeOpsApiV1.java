package com.liverpool.ms_clp_blp.shared.cachepurge.adapters.rest.v1;

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
 * OpenAPI contract for {@link CachePurgeOpsControllerV1} (Rule 8.1).
 *
 * <p>Operator surface, not part of the frontend contract — it invalidates this service's content on
 * the Akamai edge so a CMS change, or a bad response that got cached, does not have to wait out its
 * TTL.</p>
 */
@Tag(name = "Ops", description = "Operator-triggered maintenance actions. Not part of the frontend contract.")
public interface CachePurgeOpsApiV1 {

    String ACTION_DESCRIPTION = """
            What Akamai should do with the matched objects. `invalidate` marks them stale so the edge \
            revalidates with origin and keeps serving until origin confirms a change; `delete` evicts \
            them outright, so the next request must reach origin. Defaults to `cache-purge.action`.""";

    String NETWORK_DESCRIPTION = """
            Which Akamai network to purge. Rehearse on `staging` to verify the credential and the \
            object list without touching live traffic. Defaults to `cache-purge.network`.""";

    String ASYNC_NOTE = """
            Fast Purge is asynchronous: a 200 here means Akamai **accepted** the purge, not that the \
            objects are already gone from the edge. `estimatedSeconds` is Akamai's own propagation \
            estimate.""";

    String PURGE_EXAMPLE = """
            {
              "action": "INVALIDATE",
              "network": "PRODUCTION",
              "totalObjects": 1,
              "purges": [
                {
                  "targetKind": "TAG",
                  "objects": ["ms-clp-blp"],
                  "purgeId": "8c4bfc4a-26b2-11e7-94d7-276f2f54d938",
                  "supportId": "17PY1234567890123456-123456790",
                  "estimatedSeconds": 5
                }
              ]
            }""";

    @Operation(summary = "Purge this service's content from the edge",
            description = "Purges the configured endpoint paths (`cache-purge.paths`, empty by default: "
                    + "landing pages have one URL per category) by URL **and** the configured `Edge-Cache-Tag` values (`ms-clp-blp`). Two calls to "
                    + "Akamai, because it separates URL and tag purges into different endpoints.\n\n"
                    + ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Akamai accepted every purge.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CachePurgeResponse.class),
                            examples = @ExampleObject(name = "accepted", value = PURGE_EXAMPLE))),
            @ApiResponse(responseCode = "400", description = "action or network names no known value.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "cache-purge.enabled is false.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Akamai credentials are not configured.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Akamai rejected the purge.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "Akamai did not answer in time.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<CachePurgeResponse> purgeAll(
            @Parameter(description = ACTION_DESCRIPTION,
                    schema = @Schema(allowableValues = {"invalidate", "delete"})) String action,
            @Parameter(description = NETWORK_DESCRIPTION,
                    schema = @Schema(allowableValues = {"staging", "production"})) String network);

    @Operation(summary = "Purge the endpoint paths only",
            description = "Purges the configured `cache-purge.paths` by URL, leaving cache tags "
                    + "alone.\n\n" + ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Akamai accepted the purge.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CachePurgeResponse.class))),
            @ApiResponse(responseCode = "400", description = "action or network names no known value.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "cache-purge.enabled is false.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<CachePurgeResponse> purgePaths(
            @Parameter(description = ACTION_DESCRIPTION,
                    schema = @Schema(allowableValues = {"invalidate", "delete"})) String action,
            @Parameter(description = NETWORK_DESCRIPTION,
                    schema = @Schema(allowableValues = {"staging", "production"})) String network);

    @Operation(summary = "Purge the cache tags only",
            description = "Purges the configured `Edge-Cache-Tag` values (`ms-clp-blp`), leaving the "
                    + "endpoint paths alone.\n\n" + ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Akamai accepted the purge.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CachePurgeResponse.class))),
            @ApiResponse(responseCode = "400", description = "action or network names no known value.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "cache-purge.enabled is false.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<CachePurgeResponse> purgeTags(
            @Parameter(description = ACTION_DESCRIPTION,
                    schema = @Schema(allowableValues = {"invalidate", "delete"})) String action,
            @Parameter(description = NETWORK_DESCRIPTION,
                    schema = @Schema(allowableValues = {"staging", "production"})) String network);

    String OBJECTS_REQUEST_EXAMPLE = """
            {
              "site": "liverpool",
              "channel": "web",
              "alias": "liverpool-app-search",
              "type": "invalidate",
              "urls": ["https://ms-crp-clp-blp-service.liverpool.com.mx/blp/es-mx/tienda/esteelauder/catst26617625"],
              "tags": ["ms-clp-blp"]
            }""";

    @Operation(summary = "Purge the URLs and cache tags named in the body",
            description = "Purges exactly what the body names instead of the configured set. `type` "
                    + "chooses invalidate or delete. `urls` takes absolute URLs on a host in "
                    + "`cache-purge.allowed-hosts`, or paths joined onto `cache-purge.site-base-url`. "
                    + "`tags` accepts only values in `cache-purge.tags`. `site`, `channel` and `alias` "
                    + "are accepted and ignored. URLs and tags are two separate calls to Akamai, URLs "
                    + "first.\n\n" + ASYNC_NOTE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Akamai accepted every purge.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CachePurgeResponse.class))),
            @ApiResponse(responseCode = "400", description = "type or network names no known value; "
                    + "nothing to purge; a blank or malformed entry; a host or tag that is not allowed; "
                    + "or more than 200 objects.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "cache-purge.enabled is false.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Akamai credentials are not configured.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Akamai rejected the purge.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "504", description = "Akamai did not answer in time.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    ResponseEntity<CachePurgeResponse> purgeObjects(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CachePurgeObjectsRequest.class),
                            examples = @ExampleObject(name = "url and tag",
                                    value = OBJECTS_REQUEST_EXAMPLE)))
            CachePurgeObjectsRequest request,
            @Parameter(description = NETWORK_DESCRIPTION,
                    schema = @Schema(allowableValues = {"staging", "production"})) String network);
}
