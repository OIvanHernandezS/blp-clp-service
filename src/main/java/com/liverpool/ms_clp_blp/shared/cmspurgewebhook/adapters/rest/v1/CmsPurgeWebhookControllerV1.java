package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.adapters.rest.v1;

import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;
import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.ports.CmsPurgeWebhookService;

/**
 * Inbound adapter for the Contentstack callback that invalidates the Akamai edge.
 *
 * <p><strong>Contract:</strong> {@code POST /webhooks/contentstack/cache-purge}. It is a separate path
 * from the brand-flags callback so that each can be pointed at its own content types in Contentstack,
 * and so that a change to one cannot alter the other's behaviour.</p>
 *
 * <p>No {@code x-brand-id}: the purged URLs are this service's own endpoints, which every brand shares.
 * No query parameters either — a caller that could choose the action and the network could evict the
 * production edge on demand, and the caller here is a CMS webhook, not an operator. Both come from
 * {@code cms-purge-webhook.*}.</p>
 *
 * <p>Authentication is the platform's, not this endpoint's — the same inbound HTTP Basic credential
 * {@code SecurityConfig} requires of every endpoint. Like the other webhook and the ops endpoints it is
 * deliberately absent from the CORS registration: no browser is a legitimate caller here.</p>
 */
@RestController
@RequestMapping("/webhooks")
public class CmsPurgeWebhookControllerV1 implements CmsPurgeWebhookApiV1 {

    static final String HEADER_REQUEST_ID = "x-request-id";

    private final CmsPurgeWebhookService purgeWebhookService;

    /**
     * @param purgeWebhookService the inbound port this callback drives
     */
    public CmsPurgeWebhookControllerV1(CmsPurgeWebhookService purgeWebhookService) {
        this.purgeWebhookService = purgeWebhookService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @PostMapping("/contentstack/cache-purge")
    public ResponseEntity<CmsPurgeWebhookResponse> handle(
            @RequestBody(required = false) Map<String, Object> payload) {

        CmsPurgeWebhookResult result = purgeWebhookService.apply(payload == null ? Map.of() : payload);
        return ResponseEntity.ok()
                .header(HEADER_REQUEST_ID, MDC.get("requestId"))
                .body(CmsPurgeWebhookResponse.from(result));
    }
}
