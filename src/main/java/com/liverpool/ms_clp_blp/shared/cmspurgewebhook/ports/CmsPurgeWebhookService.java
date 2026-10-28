package com.liverpool.ms_clp_blp.shared.cmspurgewebhook.ports;

import java.util.Map;

import com.liverpool.ms_clp_blp.shared.cmspurgewebhook.models.CmsPurgeWebhookResult;

/**
 * Inbound port for a Contentstack delivery that may need the edge purged.
 *
 * <p>Takes the payload untyped, as the webhook receives it. Contentstack's delivery shape varies by
 * stack and by event, and a typed binding would turn an unrecognised field into a 400 — which
 * Contentstack would retry forever. The use case reads the handful of keys it needs and ignores the
 * rest.</p>
 */
public interface CmsPurgeWebhookService {

    /**
     * Decides whether one delivery warrants an edge-cache purge, and submits it if so.
     *
     * <p>Never throws: a delivery that cannot be applied comes back as a result whose
     * {@code purged} is {@code false}, because the only status this webhook may answer is 200.</p>
     *
     * @param payload the delivery body as Contentstack sent it; may be empty, never {@code null}
     * @return what the delivery caused, including when it caused nothing
     */
    CmsPurgeWebhookResult apply(Map<String, Object> payload);
}
