package com.liverpool.ms_clp_blp.landing.brandflags.adapters.rest.v1;

import java.util.Map;

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
 * OpenAPI contract for {@link ContentstackWebhookControllerV1} (Rule 8.1).
 *
 * <p>Documented because operators configure it by hand in each brand's Contentstack stack. It is not a
 * public API: nothing in the frontend calls it, and it is deliberately absent from the CORS
 * registration. Authentication is the service-wide inbound HTTP Basic credential — the same one every
 * other endpoint requires — so the only webhook-specific configuration is the brand header.</p>
 */
@Tag(name = "Webhooks",
        description = "Server-to-server callbacks. Not part of the frontend contract.")
public interface ContentstackWebhookApiV1 {

    /**
     * @param brand   brand identifier, configured as a custom header on the stack's webhook
     * @param payload the delivery body as Contentstack sends it
     * @return 200 describing what was done, including when nothing was
     */
    @Operation(
            summary = "Apply a Contentstack publish to the per-brand feature flags",
            description = "Called by Contentstack when an entry is published, unpublished or deleted. "
                    + "A publish of the configured flag content type makes this instance re-read the "
                    + "brand's entry from the content-service and store it; an unpublish or delete "
                    + "removes it, returning the brand to the service defaults. Anything else is "
                    + "acknowledged and ignored.\n\n"
                    + "**Answers 200 even when nothing was applied.** Contentstack retries every "
                    + "non-2xx response, so reporting an irrelevant event as a failure would turn it "
                    + "into a retry loop. Read `applied` and `reason` to know what happened.\n\n"
                    + "**Propagation is not instant.** Only the instance that receives the call drops "
                    + "its cached copy immediately; the others pick the change up when theirs expires, "
                    + "within `brand-flags.cache-ttl`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Delivery processed, or deliberately ignored.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BrandFlagsSyncResponse.class),
                            examples = {
                                    @ExampleObject(name = "applied", value = """
                                            {
                                              "applied": true,
                                              "action": "upsert",
                                              "brand": "GAP",
                                              "reason": "Stored 3 flag override(s)"
                                            }"""),
                                    @ExampleObject(name = "ignored", value = """
                                            {
                                              "applied": false,
                                              "action": "ignored",
                                              "brand": "GAP",
                                              "reason": "Content type 'page' is not the flag entry"
                                            }""")})),
            @ApiResponse(responseCode = "401",
                    description = "The inbound HTTP Basic credential was missing or wrong.",
                    content = @Content(mediaType = "application/problem+json"))})
    ResponseEntity<BrandFlagsSyncResponse> handle(
            // Deliberately not @NotBlank: a missing header is a stack misconfiguration, and rejecting
            // it with a 400 would make Contentstack retry the same broken delivery. It comes back as
            // applied=false with a reason instead.
            @Parameter(description = "Brand the stack belongs to, e.g. `GAP`.", required = true)
            String brand,
            Map<String, Object> payload);
}
