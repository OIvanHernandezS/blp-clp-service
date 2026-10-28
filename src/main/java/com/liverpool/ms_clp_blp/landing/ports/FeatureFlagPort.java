package com.liverpool.ms_clp_blp.landing.ports;

/**
 * Outbound port for evaluating runtime feature flags. Enables per-block enable/disable without
 * redeployment (Rule 18 feature toggle).
 *
 * <p>Evaluation is <strong>per brand</strong>: one instance serves every brand at once (the brand
 * arrives per request in {@code x-brand-id}), so a flag has no meaningful value until you say whose
 * landing page is being composed. Implementations are free to ignore the brand and answer globally —
 * the configuration-backed default did exactly that before per-brand configuration existed.</p>
 */
public interface FeatureFlagPort {

    /**
     * @param flagId the flag identifier (e.g. {@code products-list-salesforce})
     * @param brand  the brand whose page is being composed (e.g. {@code LP}); may be {@code null}
     *               when no session could be resolved, in which case the global value applies
     * @return true if the feature is enabled at runtime for that brand
     */
    boolean isEnabled(String flagId, String brand);
}
