package com.liverpool.ms_clp_blp.landing.brandflags.adapters.rest.v1;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.landing.brandflags.models.BrandFlagsSyncResult;
import com.liverpool.ms_clp_blp.landing.brandflags.ports.BrandFlagsSyncService;

/**
 * Inbound adapter for Contentstack's publish callback.
 *
 * <p><strong>Contract:</strong> {@code POST /webhooks/contentstack}</p>
 *
 * <p>Follows the shape ms_pdp_search already runs ({@code WebhooksController}): the brand arrives in
 * {@code x-brand-id}, configured as a custom header on each stack's webhook, and the body stays an
 * untyped map because Contentstack owns its shape.</p>
 *
 * <p><strong>Authentication is the platform's, not this endpoint's.</strong> The inbound HTTP Basic
 * credential that {@code SecurityConfig} requires of every endpoint is the only credential here —
 * Contentstack presents it like any other caller. There is deliberately no second, webhook-specific
 * secret: one mechanism, one credential to rotate, and nothing for an operator to get subtly wrong
 * when configuring thirteen stacks.</p>
 *
 * <p>Two things that are easy to undo by accident:</p>
 * <ul>
 *   <li><strong>It is not registered for CORS.</strong> {@code SecurityConfig} lists CORS paths
 *       explicitly and this is not one of them, so a browser cannot reach it. That is the intent —
 *       the only caller is Contentstack, server to server.</li>
 *   <li><strong>It answers 200 even when nothing is applied.</strong> Contentstack retries every
 *       non-2xx, so an irrelevant delivery reported as a failure becomes a retry loop.</li>
 * </ul>
 */
@RestController
@RequestMapping("/webhooks")
public class ContentstackWebhookControllerV1 implements ContentstackWebhookApiV1 {

    static final String HEADER_REQUEST_ID = "x-request-id";

    private final BrandFlagsSyncService syncService;

    /**
     * @param syncService applies the delivery to the per-brand flag store
     */
    public ContentstackWebhookControllerV1(BrandFlagsSyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/contentstack")
    public ResponseEntity<BrandFlagsSyncResponse> handle(
            @RequestHeader(name = "x-brand-id", required = false) String brand,
            @RequestBody(required = false) Map<String, Object> payload) {

        BrandFlagsSyncResult result =
                syncService.apply(brand, payload == null ? Map.of() : payload);
        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(BrandFlagsSyncResponse.from(result));
    }
}
