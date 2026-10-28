package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.adapters.rest.v1;

import java.util.Map;

import org.springframework.http.ResponseEntity;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for {@link CmsPurgeWebhookControllerV1} (Rule 8.1).
 *
 * <p>Documented because operators configure it by hand in the Contentstack stack. It is not a public
 * API: nothing in the frontend calls it, and it is deliberately absent from the CORS registration.
 * Authentication is the service-wide inbound HTTP Basic credential — the same one every other endpoint
 * requires — so the webhook needs no secret of its own.</p>
 */
@Tag(name = "Webhooks",
        description = "Server-to-server callbacks. Not part of the frontend contract.")
public interface CmsPurgeWebhookApiV1 {

    /**
     * @param payload the delivery body as Contentstack sends it
     * @return 200 describing what the delivery caused, including when it caused nothing
     */
    @Operation(
            summary = "Invalidate the Akamai edge after a Contentstack publish",
            description = "Called by Contentstack when an entry is published, unpublished or deleted. "
                    + "When the entry's content type is one of `cms-purge-webhook.content-types` "
                    + "(`page_blp`, `page_clp`, `products_list` and `category_redirect` by default), this "
                    + "runs the same purge as `POST /ops/cache-purge`: the configured `Edge-Cache-Tag` "
                    + "values (`ms-clp-blp`) and any configured endpoint URLs are invalidated on the "
                    + "`cms-purge-webhook.network` network. A delivery about any "
                    + "other content type is acknowledged and dropped.\n\n"
                    + "**Every event purges, not just `publish`.** An unpublish or a delete leaves the "
                    + "edge serving content that no longer exists, which is the more visible of the "
                    + "two failures.\n\n"
                    + "**Answers 200 even when nothing was purged**, including when the CDN rejected "
                    + "the purge. Contentstack retries every non-2xx, so reporting a failure here "
                    + "would turn a bad credential into a retry loop that cannot fix it. Read `purged` "
                    + "and `reason`, and the `CMS_PURGE_TRIGGERED` / `CMS_PURGE_FAILED` log events.\n\n"
                    + "**A burst is collapsed.** Publishing a template with many blocks delivers one "
                    + "webhook per entry; deliveries arriving within `cms-purge-webhook.cooldown` of a "
                    + "submitted purge are reported as ignored, because that purge already covers them."
                    + "\n\n**The purge is asynchronous.** A 200 with `purged: true` means Akamai "
                    + "accepted it, not that the edge has finished revalidating.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Delivery processed, or deliberately ignored.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = CmsPurgeWebhookResponse.class),
                            examples = {
                                    @ExampleObject(name = "purged", value = """
                                            {
                                              "purged": true,
                                              "contentType": "flex",
                                              "event": "publish",
                                              "action": "invalidate",
                                              "network": "production",
                                              "objectCount": 3,
                                              "reason": "Submitted invalidate for 3 URL(s) on the production network"
                                            }"""),
                                    @ExampleObject(name = "ignored", value = """
                                            {
                                              "purged": false,
                                              "contentType": "ms_clp_blp_flags",
                                              "event": "publish",
                                              "action": null,
                                              "network": null,
                                              "objectCount": 0,
                                              "reason": "Content type 'ms_clp_blp_flags' does not front any cached ms-clp-blp endpoint; the ones that do are [page, flex]"
                                            }"""),
                                    @ExampleObject(name = "failed", value = """
                                            {
                                              "purged": false,
                                              "contentType": "page",
                                              "event": "publish",
                                              "action": "invalidate",
                                              "network": "production",
                                              "objectCount": 0,
                                              "reason": "The CDN rejected the purge credential."
                                            }""")})),
            @ApiResponse(responseCode = "401",
                    description = "The inbound HTTP Basic credential was missing or wrong.",
                    content = @Content(mediaType = "application/problem+json"))})
    ResponseEntity<CmsPurgeWebhookResponse> handle(Map<String, Object> payload);
}
